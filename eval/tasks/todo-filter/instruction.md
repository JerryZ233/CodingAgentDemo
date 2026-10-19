Add TodoStore.listByStatus(boolean done), preserving insertion order. Extend TodoCli <file> list [open|done] to filter by status. list without a filter must still show all items. Keep add, done, IDs, and persistence behavior. Text inputs are nonempty single-line strings without tabs. Output one item per line as id<TAB>open-or-done<TAB>text.

Only edit src/. Public tests are in public-tests/. Run `sh test.sh` for a local check. Preserve existing APIs. Hidden verification runs independently after you finish.
