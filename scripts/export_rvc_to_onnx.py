#!/usr/bin/env python3
"""
Export RVC .pth models to ONNX for Android inference.
Runs automatically via GitHub Actions when .pth files are pushed to models/pth/.

Usage (manual):
    python export_rvc_to_onnx.py \
        --rvc-src /path/to/rvc-webui \
        --models model1.pth model2.pth \
        --output app/src/main/assets/models
"""
import argparse
import json
import os
import sys
import glob

import torch
import torch.nn as nn


# ── Argument parsing ──────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--rvc-src", required=True,
                   help="Path to cloned RVC WebUI repo (for architecture code)")
    p.add_argument("--models", nargs="+", required=True,
                   help="One or more .pth files to convert")
    p.add_argument("--output", required=True,
                   help="Output directory for .onnx + .json files")
    return p.parse_args()


# ── HuBERT export ─────────────────────────────────────────────────────────────

def export_hubert(out_dir: str):
    hubert_out = os.path.join(out_dir, "hubert.onnx")
    if os.path.exists(hubert_out):
        print("  [skip] hubert.onnx already exists")
        return

    print("  Exporting HuBERT via torchaudio...")
    import torchaudio

    bundle = torchaudio.pipelines.HUBERT_BASE
    model  = bundle.get_model().eval()

    class HubertWrapper(nn.Module):
        def __init__(self, m): super().__init__(); self.m = m
        def forward(self, x):
            features, _ = self.m.extract_features(x)
            return features[-1]   # last transformer layer → (1, T, 768)

    dummy   = torch.zeros(1, 16000)
    wrapper = HubertWrapper(model)

    torch.onnx.export(
        wrapper, (dummy,),
        hubert_out,
        input_names=["audio"],
        output_names=["features"],
        dynamic_axes={"audio": {1: "samples"}, "features": {1: "time"}},
        opset_version=14,
    )
    print(f"  ✓ hubert.onnx  ({os.path.getsize(hubert_out) // 1_048_576} MB)")


# ── Synthesizer export ────────────────────────────────────────────────────────

def load_checkpoint(path: str):
    cpt     = torch.load(path, map_location="cpu")
    tgt_sr  = cpt["config"][-1]
    version = cpt.get("version", "v1")
    if_f0   = cpt.get("f0", 1)
    phone_dim = 768 if version == "v2" else 256

    try:
        from infer.lib.infer_pack.models import (
            SynthesizerTrnMs256NSFsid,
            SynthesizerTrnMs768NSFsid,
            SynthesizerTrnMs256NSFsid_nono,
            SynthesizerTrnMs768NSFsid_nono,
        )
        if version == "v1":
            cls = SynthesizerTrnMs256NSFsid     if if_f0 else SynthesizerTrnMs256NSFsid_nono
        else:
            cls = SynthesizerTrnMs768NSFsid     if if_f0 else SynthesizerTrnMs768NSFsid_nono

        net_g = cls(*cpt["config"], is_half=False)
        net_g.eval()
        net_g.load_state_dict(cpt["weight"], strict=False)
        return net_g, tgt_sr, version, phone_dim, if_f0

    except Exception as e:
        print(f"  [error] Could not instantiate synthesizer: {e}")
        return None, tgt_sr, version, phone_dim, if_f0


class SynthWrapper(nn.Module):
    def __init__(self, net_g): super().__init__(); self.g = net_g
    def forward(self, phone, phone_lengths, pitch, pitchf, ds):
        audio, *_ = self.g.infer(phone, phone_lengths, pitch, pitchf, ds)
        return audio.squeeze(1)


class SynthWrapperNoPitch(nn.Module):
    def __init__(self, net_g): super().__init__(); self.g = net_g
    def forward(self, phone, phone_lengths, ds):
        audio, *_ = self.g.infer(phone, phone_lengths, ds)
        return audio.squeeze(1)


def export_synthesizer(net_g, out_path: str, phone_dim: int, if_f0: int):
    T = 100
    phone     = torch.zeros(1, T, phone_dim)
    phone_len = torch.LongTensor([T])
    ds        = torch.LongTensor([0])

    if if_f0:
        pitch  = torch.zeros(1, T, dtype=torch.long)
        pitchf = torch.zeros(1, T)
        wrapper = SynthWrapper(net_g)
        inputs  = (phone, phone_len, pitch, pitchf, ds)
        in_names  = ["phone", "phone_lengths", "pitch", "pitchf", "ds"]
        dyn_axes  = {"phone": {1: "T"}, "pitch": {1: "T"}, "pitchf": {1: "T"}}
    else:
        wrapper  = SynthWrapperNoPitch(net_g)
        inputs   = (phone, phone_len, ds)
        in_names = ["phone", "phone_lengths", "ds"]
        dyn_axes = {"phone": {1: "T"}}

    torch.onnx.export(
        wrapper, inputs, out_path,
        input_names=in_names,
        output_names=["audio"],
        dynamic_axes=dyn_axes,
        opset_version=14,
    )
    print(f"  ✓ {os.path.basename(out_path)}  ({os.path.getsize(out_path) // 1_048_576} MB)")


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    os.makedirs(args.output, exist_ok=True)
    sys.path.insert(0, args.rvc_src)

    print("\n=== HuBERT ===")
    export_hubert(args.output)

    pth_files = []
    for pattern in args.models:
        pth_files.extend(glob.glob(pattern))
    pth_files = [f for f in pth_files if f.endswith(".pth")]

    if not pth_files:
        print("No .pth files found.")
        return

    for pth in pth_files:
        stem      = os.path.splitext(os.path.basename(pth))[0]
        onnx_path = os.path.join(args.output, f"{stem}.onnx")
        json_path = os.path.join(args.output, f"{stem}.json")
        print(f"\n=== {stem} ===")

        if os.path.exists(onnx_path) and os.path.exists(json_path):
            print("  [skip] already converted")
            continue

        net_g, sr, version, phone_dim, if_f0 = load_checkpoint(pth)
        if net_g is None:
            continue

        try:
            export_synthesizer(net_g, onnx_path, phone_dim, if_f0)
            with open(json_path, "w") as f:
                json.dump({"sr": sr, "phone_dim": phone_dim,
                           "version": version, "f0": bool(if_f0)}, f)
            print(f"  ✓ {stem}.json")
        except Exception as e:
            print(f"  [error] {e}")
            if os.path.exists(onnx_path):
                os.remove(onnx_path)

    print("\nDone.")


if __name__ == "__main__":
    main()
