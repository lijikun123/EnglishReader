# Private built-in dictionary

`kreader_dictionary.bin` is an optional gzip-compressed build input generated with:

```bash
python scripts/build_dictionary.py path/to/kreader_dict.csv \
  --android-output app/src/main/assets/kreader_dictionary.bin
```

The generated asset and source CSV are intentionally ignored by Git. When the
asset is present, KReader copies the read-only dictionary database into private
app storage on first use. A source build without it keeps the small sample
dictionary and the existing CSV/JSON import feature.
