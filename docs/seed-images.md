# Demo catalogue: image sources and licences

`DemoDataset` seeds 20 technology products. Each one points at a photo on Wikimedia Commons through
`https://commons.wikimedia.org/wiki/Special:FilePath/<file>?width=800`, which redirects to a resized
copy on `upload.wikimedia.org` (public, stable, no hotlink protection). `Product.images` stores URLs,
so nothing is uploaded to MinIO at seed time. Every URL was checked with `curl -sIL` (HTTP 200,
`image/jpeg`) when the catalogue was written.

All files are free licences that allow reuse with attribution; the licence and author are shown on
each file's Commons page (`https://commons.wikimedia.org/wiki/File:<file>`).

| SKU | Commons file | Licence | Author |
|---|---|---|---|
| TEC-001 | Mi Band 6 default face.jpg | CC BY-SA 4.0 | 人工知能 |
| TEC-002 | Logitech M185 mouse HS01.jpg | CC BY 4.0 | Hayden Schiff |
| TEC-003 | Keychron K8 Non-Backlight Wireless Mechanical Keyboard - 52772912798.jpg | CC BY 2.0 | Triple-green |
| TEC-004 | Logitech K120 Keyboard.jpg | CC BY-SA 4.0 | TaurusEmerald |
| TEC-005 | ASUS PB278Q (PXL 20250914 223256075).jpg | CC BY 4.0 | Hayden Schiff |
| TEC-006 | Webcam (Logitech c922).jpg | CC0 | Zsinytwiki |
| TEC-007 | Sony WH-CH510 Bluetooth Over-Ear Headphone.jpg | CC BY-SA 4.0 | RPSkokie |
| TEC-008 | Sound BlasterX H5 Gaming Headset.jpg | CC BY-SA 3.0 | Dinopkk |
| TEC-009 | JBL GO2 Bluetooth speaker 00 10 21 131000.jpeg | CC BY-SA 4.0 | Pittigrilli |
| TEC-010 | Raspberry Pi 4 Model B - Top.jpg | CC BY-SA 4.0 | Laserlicht |
| TEC-011 | Samsung T5 Portable External SSD (Solid State Drive) (43544308485).jpg | CC BY 2.0 | Tony Webster |
| TEC-012 | Kingston Technology DataTraveler G4 USB flash drive USB 3.0 32 Gb.jpg | CC BY-SA 4.0 | Dmitry Makeev |
| TEC-013 | SanDisk Ultra 32GB MicroSD card with Adapter - 2015.jpg | CC0 | Choe Kwangmo |
| TEC-014 | TP-Link TL-WR740N router HS1.jpg | CC BY 4.0 | Hayden Schiff |
| OTR-001 | Xiaomi 10000 mAh Power Bank.jpg | CC BY-SA 4.0 | YipC |
| OTR-002 | Computer HDMI cable (2026).jpg | CC BY-SA 4.0 | Gpkp |
| OTR-003 | CAT8 Ethernet Cable-5839.jpg | CC BY-SA 4.0 | Raimond Spekking |
| OTR-004 | Black charging cable type USB-C plugs 01.jpg | CC BY-SA 4.0 | Chenspec |
| OTR-005 | USB hub Gembird.jpg | CC0 | А. Кабанов |
| OTR-006 | Wireless Charger white front.jpg | CC BY-SA 4.0 | 茅野ふたば |

Prices are indicative ARS figures; dimensions and weight are the boxed product. The catalogue only
uses the existing `ProductCategory` values: gadgets and devices are `TECNOLOGIA`, cables and small
accessories are `OTROS`.
