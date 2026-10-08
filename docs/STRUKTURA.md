# Struktura runnera

Runner będzie osobnym serwisem od backendu. Na start układ jest prosty:

- `core/` — główny flow przyjęcia i wykonania submissionu
- `languages/` — obsługa konkretnych języków
- `languages/java/` — pierwszy język w MVP
- `sandbox/` — izolacja, limity i uruchamianie kodu
- `model/` — dane wejściowe i wynik wykonania
- `tests/` — testy runnera
- `docs/` — opis działania i decyzji technicznych

Najpierw robimy cały flow dla Javy. C++ i Python dołożymy dopiero jak Java będzie działała od początku do końca.
