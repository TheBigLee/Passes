#!/usr/bin/env python3
"""Generate sample .pkpass files covering every pass type the app supports.

A .pkpass is a zip of pass.json + images (+ manifest/signature, which this app does
not verify, so we omit them). Output: ../sample-passes/*.pkpass

Run: python3 tools/generate_test_passes.py
"""
import json, os, struct, zlib, zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "sample-passes")

def png(w, h, rgb):
    """Minimal solid-colour PNG bytes (pure stdlib)."""
    r, g, b = rgb
    def chunk(typ, data):
        return struct.pack(">I", len(data)) + typ + data + struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff)
    raw = b"".join(b"\x00" + bytes([r, g, b]) * w for _ in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

def rgb(r, g, b):
    return f"rgb({r},{g},{b})"

def f(key, label, value):
    return {"key": key, "label": label, "value": value}

def build(name, *, bg, fg, org, desc, kind, structure, barcode_fmt, barcode_msg,
          relevant=None, transit=None, strip_rgb=None, logo_rgb=(255, 255, 255)):
    pj = {
        "formatVersion": 1,
        "passTypeIdentifier": f"pass.ch.bigli.sample.{name}",
        "serialNumber": f"SAMPLE-{name.upper()}-0001",
        "teamIdentifier": "SAMPLETEAM",
        "organizationName": org,
        "description": desc,
        "backgroundColor": bg,
        "foregroundColor": fg,
        "labelColor": fg,
        "barcode": {"format": barcode_fmt, "message": barcode_msg, "messageEncoding": "iso-8859-1", "altText": barcode_msg},
        "barcodes": [{"format": barcode_fmt, "message": barcode_msg, "messageEncoding": "iso-8859-1", "altText": barcode_msg}],
    }
    if relevant:
        pj["relevantDate"] = relevant
    body = dict(structure)
    if transit:
        body["transitType"] = transit
    pj[kind] = body

    files = {
        "pass.json": json.dumps(pj, ensure_ascii=False, indent=2).encode("utf-8"),
        "icon.png": png(29, 29, logo_rgb),
        "icon@2x.png": png(58, 58, logo_rgb),
        "logo.png": png(160, 50, logo_rgb),
        "logo@2x.png": png(320, 100, logo_rgb),
    }
    if strip_rgb:
        files["strip.png"] = png(375, 123, strip_rgb)
        files["strip@2x.png"] = png(750, 246, strip_rgb)

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.pkpass")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for fn, data in files.items():
            z.writestr(fn, data)
    return os.path.basename(path)


def boarding(name, transit, bg, org, o_code, o_city, d_code, d_city, fmt, msg, extra_sec):
    return build(
        name, bg=bg, fg=rgb(255, 255, 255), org=org,
        desc=f"{org} boarding pass {o_code} to {d_code}", kind="boardingPass", transit=transit,
        barcode_fmt=fmt, barcode_msg=msg, relevant="2026-08-15T08:45:00Z",
        structure={
            "headerFields": [f("boards", "BOARDS", "08:45")],
            "primaryFields": [f("origin", o_code, o_city), f("dest", d_code, d_city)],
            "secondaryFields": extra_sec,
            "auxiliaryFields": [f("date", "DATE", "15 Aug"), f("class", "CLASS", "Economy")],
        },
    )

made = []
# --- Boarding passes: all transit sub-types ---
made.append(boarding("boarding-air", "PKTransitTypeAir", rgb(26, 115, 232), "SWISS",
                     "ZRH", "Zürich", "JFK", "New York", "PKBarcodeFormatPDF417", "M1SWISS ZRHJFK 0074",
                     [f("gate", "GATE", "A12"), f("seat", "SEAT", "14C")]))
made.append(boarding("boarding-train", "PKTransitTypeTrain", rgb(200, 16, 46), "SBB",
                     "GVA", "Genève", "ZUE", "Zürich HB", "PKBarcodeFormatAztec", "SBB-2582-0957500030",
                     [f("coach", "COACH", "7"), f("seat", "SEAT", "62"), f("platform", "PLATFORM", "3")]))
made.append(boarding("boarding-bus", "PKTransitTypeBus", rgb(230, 126, 0), "FlixBus",
                     "BRN", "Bern", "ZUR", "Zürich", "PKBarcodeFormatCode128", "FLIX-889012345",
                     [f("platform", "STAND", "B4"), f("seat", "SEAT", "22")]))
made.append(boarding("boarding-boat", "PKTransitTypeBoat", rgb(0, 150, 136), "CalMac Ferries",
                     "CAL", "Calais", "DOV", "Dover", "PKBarcodeFormatAztec", "FERRY-4471-DECKA",
                     [f("deck", "DECK", "5"), f("cabin", "CABIN", "512")]))
made.append(boarding("boarding-generic", "PKTransitTypeGeneric", rgb(69, 90, 100), "Metro Transit",
                     "CTR", "Centre", "APT", "Airport", "PKBarcodeFormatQR", "METRO-GEN-778210",
                     [f("zone", "ZONE", "A-B"), f("seat", "COACH", "2")]))

# --- Event ticket ---
made.append(build("event-ticket", bg=rgb(123, 31, 162), fg=rgb(255, 255, 255), org="Basel Tattoo",
                  desc="Basel Tattoo evening show", kind="eventTicket",
                  barcode_fmt="PKBarcodeFormatQR", barcode_msg="TATTOO-0957500030551", relevant="2026-07-18T20:00:00Z",
                  strip_rgb=(156, 39, 176),
                  structure={
                      "headerFields": [f("door", "DOORS", "18:30")],
                      "primaryFields": [f("event", "EVENT", "Basel Tattoo")],
                      "secondaryFields": [f("date", "DATE", "18 Jul 2026"), f("time", "TIME", "20:00")],
                      "auxiliaryFields": [f("section", "SECTION", "B"), f("row", "ROW", "12"), f("seat", "SEAT", "5")],
                  }))

# --- Store card (loyalty) ---
made.append(build("store-card", bg=rgb(46, 125, 50), fg=rgb(255, 255, 255), org="Coop",
                  desc="Coop Supercard", kind="storeCard",
                  barcode_fmt="PKBarcodeFormatCode128", barcode_msg="6001234567890", relevant=None,
                  strip_rgb=(56, 142, 60),
                  structure={
                      "primaryFields": [],
                      "secondaryFields": [f("balance", "BALANCE", "CHF 42.50"), f("points", "POINTS", "1240")],
                      "auxiliaryFields": [f("member", "MEMBER", "Nicolas B."), f("since", "SINCE", "2019")],
                  }))

# --- Coupon ---
made.append(build("coupon", bg=rgb(216, 27, 96), fg=rgb(255, 255, 255), org="H&M",
                  desc="20% off everything", kind="coupon",
                  barcode_fmt="PKBarcodeFormatQR", barcode_msg="HM-SAVE20-778210", relevant="2026-12-31T22:59:00Z",
                  strip_rgb=(233, 30, 99),
                  structure={
                      "primaryFields": [],
                      "secondaryFields": [f("offer", "OFFER", "20% OFF"), f("code", "CODE", "SAVE20")],
                      "auxiliaryFields": [f("expires", "EXPIRES", "31 Dec 2026")],
                  }))

# --- Generic ---
made.append(build("generic", bg=rgb(55, 71, 79), fg=rgb(255, 255, 255), org="Fitnesspark",
                  desc="Gym Membership", kind="generic",
                  barcode_fmt="PKBarcodeFormatQR", barcode_msg="GYM-MEMBER-44210", relevant=None,
                  strip_rgb=(69, 90, 100),
                  structure={
                      "primaryFields": [],
                      "secondaryFields": [f("member", "MEMBER", "Nicolas B."), f("plan", "PLAN", "Premium")],
                      "auxiliaryFields": [f("valid", "VALID UNTIL", "01 Jan 2027"), f("id", "ID", "44210")],
                  }))

print(f"Wrote {len(made)} pkpass files to {os.path.normpath(OUT)}:")
for m in made:
    print("  " + m)
