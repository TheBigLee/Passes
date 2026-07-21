# Sample passes

Test `.pkpass` files covering every supported type, for manually checking the app renders
each nicely. Regenerate with `python3 tools/generate_test_passes.py`.

| File | Type | Notes |
|------|------|-------|
| `boarding-air.pkpass` | boardingPass · air | ZRH→JFK, gate/seat/boards, PDF417 |
| `boarding-train.pkpass` | boardingPass · train | GVA→ZUE, coach/seat/platform, Aztec |
| `boarding-bus.pkpass` | boardingPass · bus | Bern→Zürich, stand/seat, Code128 |
| `boarding-boat.pkpass` | boardingPass · boat | Calais→Dover, deck/cabin, Aztec |
| `boarding-generic.pkpass` | boardingPass · generic | transit, QR |
| `event-ticket.pkpass` | eventTicket | date/time/section/row/seat, strip, QR |
| `store-card.pkpass` | storeCard | balance/points/member, strip, Code128 |
| `coupon.pkpass` | coupon | offer/code/expiry, strip, QR |
| `generic.pkpass` | generic | membership, strip, QR |

Note: these are unsigned (no `manifest.json`/`signature`) — fine because the app doesn't
verify signatures. They exercise all barcode formats (QR/PDF417/Aztec/Code128), colours,
`relevantDate`, and header/primary/secondary/auxiliary fields.
