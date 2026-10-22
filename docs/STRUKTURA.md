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
zgodnie z kontraktem z zadania #5. Tymczasowe modele szkieletu zastąpiono
modelami właściwego żądania i odpowiedzi.

`RunRequest` zbiera identyfikatory, `Language`, pliki, listę `TestSuite` oraz
`ExecutionLimits`. Zestaw testowy zawiera `TestVisibility`, deklaracje
`TestCaseDefinition` i metadane punktu wejścia: `JavaExecution`, `CppExecution`
lub `PythonExecution`, wybrane przez pole JSON `profile` interfejsu
`ExecutionProfile`. Są to wyłącznie modele danych. Nie dodają obsługi wykonania
tych języków. `RunRequestValidator.validateFiles` w `core/` sprawdza bezpieczne
ścieżki, duplikaty i kolizje plików z katalogami oraz nadpisywanie wyłącznie
zadeklarowanych plików `EDITABLE`. Dopuszcza brak nadpisań i pustą treść
nadpisywanego pliku. Komunikaty walidacji nie ujawniają ścieżek ani treści.
Walidator nie jest jeszcze podłączony do HTTP. Walidacja metadanych, powiązań
zestawów testowych, limitów rozmiaru i rzeczywistego systemu plików pozostaje
osobnym etapem; samo sprawdzenie tekstu ścieżki nie chroni przed dowiązaniami.

`Verdict` opisuje ogólny werdykt rozwiązania, a `TestVerdict` status pojedynczego
przypadku (w tym `NOT_RUN`, bez `COMPILATION_ERROR`). `TestResult` przechowuje
wynik przypadku i diagnostykę jako wartości JSON dowolnego typu. Dla `HIDDEN`
nie pozwala utworzyć wyniku zawierającego dane diagnostyczne inne niż `null`.
To zabezpieczenie modelu, a nie kompletna obsługa filtrowania raportów i logów.
`RunResponse` zawiera identyfikatory, wersję kontraktu i podsumowanie `RunResult`.
Podsumowanie przyjmuje wyłącznie jawne wyniki i pilnuje zgodności liczników.
`Benchmark` i `BenchmarkMetrics` opisują politykę oraz dane pomiarowe, z enumami
stanu, przyczyny, zakresu i źródła pomiaru. `GradingIntegrity` opisuje granicę
zaufania do oceny. Run wymaga benchmarku `NOT_REQUESTED`; same modele nie
wykonują żadnych pomiarów. Niedostępne pomiary mają wartość `null`, nie zero.

Na tym etapie nie ma kompilacji ani wykonywania kodu uczestników, API Run/Submit,
integracji Judge0, bazy danych ani kolejki. Instrukcje budowania, testowania
i uruchomienia znajdują się w głównym README.

Najpierw robimy cały flow dla Javy. C++ i Python dołożymy dopiero jak Java będzie działała od początku do końca.
