Fix CsvParser.parse(String) to support quoted CSV fields containing commas, escaped double quotes (two quotes represent one), and newlines inside quoted fields. Preserve unquoted fields, empty fields, CRLF row separators, empty input, and trailing newline behavior. Inputs are valid CSV; malformed-input handling is out of scope.

Only edit src/. Public tests are in public-tests/. Run `sh test.sh` for a local check. Preserve existing APIs. Hidden verification runs independently after you finish.
