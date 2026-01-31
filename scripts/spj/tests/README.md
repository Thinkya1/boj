# SPJ Checker Tests

## Build

```bash
g++ -std=gnu++17 -O2 ..\\checker.cpp -o ..\\checker.exe
```

## Run

### Float epsilon (0.01)
```bash
..\\checker.exe input_empty.txt out_float_ok.txt ans_float.txt spj_float.conf
..\\checker.exe input_empty.txt out_float_wa.txt ans_float.txt spj_float.conf
```

### Ignore order (TOKEN)
```bash
..\\checker.exe input_empty.txt out_token_ok.txt ans_token.txt spj_token.conf
..\\checker.exe input_empty.txt out_token_wa.txt ans_token.txt spj_token.conf
```

### Ignore order (LINE)
```bash
..\\checker.exe input_empty.txt out_line_ok.txt ans_line.txt spj_line.conf
..\\checker.exe input_empty.txt out_line_wa.txt ans_line.txt spj_line.conf
```

### Normal token compare
```bash
..\\checker.exe input_empty.txt out_normal_ok.txt ans_normal.txt spj_normal.conf
..\\checker.exe input_empty.txt out_normal_wa.txt ans_normal.txt spj_normal.conf
```
