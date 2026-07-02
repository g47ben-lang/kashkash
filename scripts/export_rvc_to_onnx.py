#!/usr/bin/env python3
"""
Export RVC .pth models → ONNX for Android deployment.

STEP 1: Clone the RVC WebUI repo on your PC (required for model classes):
    git clone https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI
    cd Retrieval-based-Voice-Conversion-WebUI
    pip install -r requirements.txt
    pip install onnx onnxruntime

STEP 2: Run this script from inside that directory:
    python path/to/this/export_rvc_to_onnx.py \
        --pth  "C:/Downloads/bibi/bibi.pth" \
                "C:/Downloads/bibi (1)/bibi_v2_yr_e225_s49050.pth" \
                "C:/Downloads/sara_netan_v2_e425_s27625/sara_netan_v2_e425_s27625.pth" \
                "C:/Downloads/donaldtrumplowenergy/donaldtrumplowenergy.pth" \
        --output "C:/onnx_models"

STEP 3: Connect phone via USB, copy everything from --output to:
    /sdcard/Android/data/com.voicechanger.app/files/models/
    (or com.voicechanger.app.debug/files/models/ for debug builds)

NOTE: The .index files are NOT needed by the Android app — skip them.
      The __MACOSX folders are junk — ignore them.
"""

import sys, os, json, argparse
from pathlib import Path

# ── Verify we are in the RVC WebUI directory ───────────────────────────────
def check_rvc_env():
    try:
        from infer.lib.infer_pack import models as rvc_models  # noqa
        return True
    except ImportError:
        return False

# ── Load .pth and instantiate the synthesizer ──────────────────────────────
def load_synthesizer(pth_path: str):
    import torch
    from infer.lib.infer_pack.models import (
        SynthesizerTrnMs256NSFsid,
        SynthesizerTrnMs768NSFsid,
        SynthesizerTrnMs256NSFsid_nono,
        SynthesizerTrnMs768NSFsid_nono,
    )

    cpt  = torch.load(pth_path, map_location="cpu")
    cfg  = cpt.get("config", [])
    ver  = cpt.get("version", "v1")
    f0   = bool(cpt.get("f0", 1))
    sr   = int(cpt.get("sr",  40000))

    if ver == "v1":
        cls = SynthesizerTrnMs256NSFsid      if f0 else SynthesizerTrnMs256NSFsid_nono
    else:
        cls = SynthesizerTrnMs768NSFsid      if f0 else SynthesizerTrnMs768NSFsid_nono

    net = cls(*cfg, is_half=False)
    net.load_state_dict(cpt["weight"], strict=False)
    net.eval()

    return net, {"version": ver, "sr": sr, "f0": f0,
                 "phone_dim": 256 if ver == "v1" else 768}

# ── Export the synthesizer's infer() method to ONNX ───────────────────────
def export_synthesizer(net, meta: dict, out_path: str):
    import torch

    class _Wrapper(torch.nn.Module):
        def __init__(self, g): super().__init__(); self.g = g
        def forward(self, phone, phone_lengths, pitch, pitchf, ds):
            return self.g.infer(phone, phone_lengths, pitch, pitchf, ds)[0]

    seq = 64
    dim = meta["phone_dim"]
    phone        = torch.randn(1, seq, dim)
    phone_lengths= torch.LongTensor([seq])
    pitch        = torch.LongTensor([[60]*seq])
    pitchf       = torch.FloatTensor([[220.0]*seq])
    ds           = torch.LongTensor([0])

    wrapper = _Wrapper(net)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (phone, phone_lengths, pitch, pitchf, ds),
            out_path,
            input_names=["phone", "phone_lengths", "pitch", "pitchf", "ds"],
            output_names=["audio"],
            dynamic_axes={
                "phone":  {1: "T"},
                "pitch":  {1: "T"},
                "pitchf": {1: "T"},
                "audio":  {2: "S"},
            },
            opset_version=16,
            do_constant_folding=True,
        )
    print(f"  ✓ synthesizer → {out_path}")

# ── Export HuBERT to ONNX ─────────────────────────────────────────────────
def export_hubert(out_path: str, hubert_pt: str = None):
    import torch

    # Try fairseq first (already installed in RVC WebUI)
    try:
        import fairseq
        pt = hubert_pt or "hubert_base.pt"
        if not os.path.exists(pt):
            print("  Downloading hubert_base.pt from Meta AI …")
            import urllib.request
            url = "https://dl.fbaipublicfiles.com/hubert/hubert_base_ls960.pt"
            urllib.request.urlretrieve(url, pt)
        models, _, _ = fairseq.checkpoint_utils.load_model_ensemble_and_task([pt], suffix="")
        hubert = models[0].eval()

        class _FairseqWrapper(torch.nn.Module):
            def __init__(self, m): super().__init__(); self.m = m
            def forward(self, x):
                feats, _ = self.m.extract_features(x, output_layer=9)
                return feats

        wrapper = _FairseqWrapper(hubert)

    except Exception as e:
        print(f"  fairseq unavailable ({e}), trying torchaudio …")
        import torchaudio
        bundle  = torchaudio.pipelines.HUBERT_BASE
        hubert  = bundle.get_model().eval()

        class _TaWrapper(torch.nn.Module):
            def __init__(self, m): super().__init__(); self.m = m
            def forward(self, x):
                feats, _ = self.m.extract_features(x)
                return feats[-1]

        wrapper = _TaWrapper(hubert)

    dummy = torch.zeros(1, 16000)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy,),
            out_path,
            input_names=["audio"],
            output_names=["features"],
            dynamic_axes={"audio": {1: "L"}, "features": {1: "T"}},
            opset_version=16,
            do_constant_folding=True,
        )
    print(f"  ✓ HuBERT → {out_path}")

# ── Optionally simplify ONNX (reduces model size & speeds up inference) ───
def try_simplify(path: str):
    try:
        import onnx
        from onnxsim import simplify
        model = onnx.load(path)
        model_sim, ok = simplify(model)
        if ok:
            onnx.save(model_sim, path)
            print(f"  ✓ simplified {Path(path).name}")
    except ImportError:
        pass  # onnxsim not installed, skip

# ── Main ──────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pth",     nargs="+", required=True, help="Paths to .pth files")
    ap.add_argument("--output",  default="./onnx_models",  help="Output directory")
    ap.add_argument("--hubert",  default=None, help="Optional: path to hubert_base.pt")
    args = ap.parse_args()

    if not check_rvc_env():
        print("\nERROR: RVC model classes not found.")
        print("Run this script from the cloned RVC WebUI directory:")
        print("  git clone https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI")
        print("  cd Retrieval-based-Voice-Conversion-WebUI")
        print("  pip install -r requirements.txt && pip install onnx onnxruntime")
        sys.exit(1)

    os.makedirs(args.output, exist_ok=True)

    # Export HuBERT (shared by all voice models)
    hubert_out = os.path.join(args.output, "hubert.onnx")
    if os.path.exists(hubert_out):
        print(f"HuBERT already exported, skipping.")
    else:
        print("Exporting HuBERT …")
        export_hubert(hubert_out, args.hubert)
        try_simplify(hubert_out)

    # Export each voice model
    meta_all = {}
    for pth in args.pth:
        name = Path(pth).stem
        onnx_out = os.path.join(args.output, f"{name}.onnx")
        json_out = os.path.join(args.output, f"{name}.json")

        print(f"\nExporting  {name} …")
        net, meta = load_synthesizer(pth)
        export_synthesizer(net, meta, onnx_out)
        try_simplify(onnx_out)

        with open(json_out, "w") as f:
            json.dump(meta, f, indent=2)
        print(f"  ✓ metadata → {json_out}")
        meta_all[name] = meta

    print("\n✅  All done!")
    print(f"\nCopy the contents of  {args.output}  to your Android phone:")
    print("  /sdcard/Android/data/com.voicechanger.app/files/models/")
    print("    (for debug builds: com.voicechanger.app.debug/files/models/)")
    print("\nFiles needed per model:")
    for name, meta in meta_all.items():
        sz = Path(args.output, f"{name}.onnx").stat().st_size // (1024*1024)
        print(f"  {name}.onnx  ({sz} MB, sr={meta['sr']})")
    print("  hubert.onnx  (shared, ~90 MB)")

if __name__ == "__main__":
    main()
