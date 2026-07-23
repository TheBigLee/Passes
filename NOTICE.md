# Third-Party Notices

This file lists third-party assets bundled in this repository that require attribution
or license tracking, per the terms of their respective licenses.

## Icons (`app/src/main/res/drawable/`)

| File | Source | License | Notes |
|---|---|---|---|
| `ic_transit_train.xml` | "train_fill", [MingCute Icon](https://www.svgrepo.com/svg/432006/train) via SVG Repo | Apache License 2.0 (full text: `licenses/apache-2.0.txt`) | Converted from SVG to an Android vector drawable; geometry unchanged. SVG Repo's invisible per-download watermark path was dropped (no visual effect, not part of the artwork). |
| `ic_transit_bus.xml` | "bus-transportation", [SVG Repo](https://www.svgrepo.com/svg/) | MIT License (full text: `licenses/mit.txt`) | Converted from SVG to an Android vector drawable; geometry unchanged. Already faced right, no mirroring. |
| `ic_transit_boat.xml` | "ferry-1", [SVG Repo](https://www.svgrepo.com/svg/480891/ferry-1) | Public Domain | No attribution required; listed here for provenance only. Mirrored horizontally (original faces left; app convention faces right). |

All were converted from the original SVG to Android `<vector>` XML with tint color
removed (this app tints icons at render time via `Icon(tint = ...)`), and otherwise
preserve the original geometry.
