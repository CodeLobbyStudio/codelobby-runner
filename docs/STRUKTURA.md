# Struktura runnera

Runner jest osobnym serwisem Spring Boot, niezależnym od backendu.
Kod znajduje się w `src/main/java/pl/codelobby/runner/`:

- `core/` — główny flow przyjęcia i wykonania submissionu
- `languages/` — obsługa konkretnych języków
- `languages/java/` — pierwszy język w MVP
- `sandbox/` — izolacja, limity i uruchamianie kodu
- `model/` — dane wejściowe i wynik wykonania

Pozostałe katalogi projektu:

- `src/main/resources/` — konfiguracja aplikacji
- `src/test/java/pl/codelobby/runner/` — testy runnera, w tym start aplikacji i HTTP health check
- `docs/` — opis działania i decyzji technicznych

`RunnerApplication` jest punktem startowym. Puste pakiety opisano plikami
`package-info.java`. `TaskFile`, `SubmissionFile` i `FileType` opisują pliki
zgodnie z kontraktem z zadania #5. `ExecutionResult` pozostaje tymczasowym
modelem szkieletu i nie określa docelowego formatu wyniku HTTP ani werdyktów.

`RunRequest` zbiera identyfikatory, `Language`, pliki, listę `TestSuite` oraz
`ExecutionLimits`. Zestaw testowy zawiera `TestVisibility`, deklaracje
`TestCaseDefinition` i metadane punktu wejścia: `JavaExecution`, `CppExecution`
lub `PythonExecution`, wybrane przez pole JSON `profile` interfejsu
`ExecutionProfile`. Są to wyłącznie modele danych. Nie dodają obsługi wykonania
tych języków ani walidacji reguł między polami, ścieżek i limitów — te etapy
zostaną dodane osobno.

Na tym etapie nie ma kompilacji ani wykonywania kodu uczestników, API Run/Submit,
integracji Judge0, bazy danych ani kolejki. Instrukcje budowania, testowania
i uruchomienia znajdują się w głównym README.

Najpierw robimy cały flow dla Javy. C++ i Python dołożymy dopiero jak Java będzie działała od początku do końca.
