# Struktura runnera

Runner jest osobnym serwisem Spring Boot, niezależnym od backendu.
Kod znajduje się w `src/main/java/pl/codelobby/runner/`:

- `core/` — główny flow przyjęcia i wykonania submissionu
- `languages/` — obsługa konkretnych języków
- `languages/java/` — pierwszy język w MVP
- `sandbox/` — izolacja, limity i uruchamianie kodu
- `model/` — dane wejściowe i wynik wykonania
- `api/` — kontroler HTTP Run, uwierzytelnienie, ścisły JSON i mapowanie błędów

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
`validateTestSuites` najpierw sprawdza pliki, a następnie wyłącznie publiczne,
niepuste zestawy i przypadki z poprawnymi, unikalnymi identyfikatorami. Każdy
`VISIBLE_TEST` musi należeć do dokładnie jednego zestawu; Run odrzuca `HIDDEN_TEST`.
Profil musi odpowiadać językowi. W C++ i Pythonie plik startowy musi należeć do
danego zestawu i mieć właściwe rozszerzenie. W Javie sprawdzany jest format nazwy
klasy, bez analizy kodu i potwierdzania istnienia punktu wejścia.
`validate` łączy te kontrole z formatem `requestId`/`taskVersionId` i limitami CPU,
czasu całkowitego oraz pamięci. Sprawdzane są limity liczby plików, nadpisań,
zestawów i przypadków oraz rozmiar każdego pliku w bajtach UTF-8. Rozmiar projektu
jest liczony po nadpisaniach, bez podwójnego doliczania oryginału. Oryginały
i nadpisania muszą osobno spełniać limit pojedynczego pliku.
`RequestValidationException` zawiera `ValidationErrorCode`: `INVALID_REQUEST`,
`INVALID_SOURCE_FILES` lub `PAYLOAD_TOO_LARGE`. Komunikaty pozostają ogólne;
`RunErrorHandler` mapuje kody na odpowiedzi HTTP.
Walidator jest podłączony do HTTP przez `RunSubmissionUseCase`. Limit całego body
(2000000 bajtów) jest egzekwowany podczas odczytu w `RunController`, również bez
Content-Length, a nie przez ponowną serializację obiektu. Pozostają kontrole
rzeczywistego systemu plików;
samo sprawdzenie tekstu ścieżki nie chroni przed dowiązaniami. Walidacja profilu
nie oznacza dostępności danego języka w uruchomionym runnerze ani kompletnej
walidacji rozszerzeń źródeł i istnienia klasy wejściowej w kodzie Javy.

`SubmissionExecutor` w `core/` definiuje granicę wykonania publicznego Run:
przyjmuje uprzednio zwalidowany `RunRequest` i zwraca `RunResult`. Nie obsługuje
zadań asynchronicznego Submit. Nie ma jeszcze implementacji wykonującej kod.
Konfiguracja Spring domyślnie podłącza wykonawcę zgłaszającego brak dostępności.
`StubSubmissionExecutor` znajduje się wyłącznie w `src/test/`: zwraca wynik
ustalony przez test, bez odczytywania, kompilacji czy uruchamiania źródeł. Nie
trafia do aplikacyjnego JAR-a i nie potwierdza poprawności rozwiązania.
`SubmissionExecutorTests` sprawdza kontraktowe scenariusze sukcesu, błędnej
odpowiedzi i błędu kompilacji.
`RunSubmissionUseCase` łączy walidację z `SubmissionExecutor` i przygotowaniem
`RunResponse`: najpierw waliduje żądanie, następnie raz wywołuje wykonawcę,
a wynik opakowuje w identyfikatory z żądania i wersję kontraktu. Odrzucone
żądanie nie trafia do wykonawcy. Wyjątki wykonawcy są propagowane bez zamiany
w sukces; brak wyniku jest błędem stanu. `RunErrorHandler` mapuje awarie na ogólne
odpowiedzi HTTP. Klasa otrzymuje zależności przez konstruktor, a `RunConfiguration`
łączy ją z kontrolerem. `RunSubmissionUseCaseTests`
sprawdza pełny kształt odpowiedzi z przykładami kontraktu, korelację żądania,
zatrzymanie błędnych żądań przed wykonaniem oraz propagację awarii.

`StagedSubmissionExecutor` implementuje `SubmissionExecutor`, rozdzielając
`CompilationStage`, `ExecutionStage` i `EvaluationStage`. Dla każdego zestawu
wywołuje przygotowanie celu, a uruchomienie tylko po jego sukcesie. Nieudana
kompilacja pomija uruchomienie danego zestawu, ale nie blokuje niezależnych celów.
Następnie ocena otrzymuje niemodyfikowalną listę `SuiteExecution` w kolejności
żądania. Błędy infrastruktury i brak wyniku etapu przerywają przepływ.
`CompilationResult` zawiera sukces, niejawny identyfikator artefaktu silnika i
wewnętrzną diagnostykę. `ExecutionReport` przenosi surowy raport oraz kod wyjścia;
nie jest odpowiedzią API i nie może być przekazywany uczestnikowi bez oceny.
`SuiteExecution` wiąże te dane z zestawem i wymaga braku raportu po błędzie budowy.
Na tym etapie implementacje wszystkich trzech etapów istnieją tylko jako atrapy
w testach. Parser raportów, wyliczanie werdyktów, sanitizacja diagnostyki,
telemetria przyczyn zakończenia, egzekwowanie wspólnych budżetów, ponowne użycie
wspólnych celów i sprzątanie artefaktów pozostają do wdrożenia przy integracji
silnika. Surowy raport i kod wyjścia nie wystarczają do potwierdzenia przekroczenia
limitu pamięci lub czasu. Etapy nie są skonfigurowane jako wykonawca Spring
i nie ma prawdziwego kompilatora. `StagedSubmissionExecutorTests` sprawdza kolejność, pomijanie
niezbudowanego celu, kontynuację innych celów oraz zatrzymanie przy awarii.

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

W kontrakcie `VISIBLE_TEST` oznacza publiczny opis i wynik, a nie dostęp do kodu
testu. Backend przekazuje oba rodzaje kodu testowego tylko runnerowi i przechowuje
opisy publiczne łączone z wynikami przez `testId`. Modele nie wymagają dodatkowych
pól. Ochrona diagnostyki przed ujawnieniem kodu testów będzie częścią wykonania;
samo ukrycie plików w przeglądarce nie zapewnia tajności we wspólnym sandboxie.

`RunController` obsługuje `POST /internal/v1/runs`. Odczytuje ograniczone body
i ścisły JSON UTF-8; odrzuca nieznane/powtórzone pola, brakujące wymagane wartości,
konwersje typów oraz dodatkowy JSON po żądaniu. `InternalApiFilter` chroni ścieżki
`/internal/` kluczem z `CODELOBBY_INTERNAL_KEY`; porównuje stałej długości skróty
SHA-256 przez `MessageDigest.isEqual`. Brak konfiguracji odrzuca wszystkie
wewnętrzne żądania, a zbyt krótki skonfigurowany klucz blokuje start aplikacji.
Health pozostaje publiczny. Odpowiedzi wewnętrzne mają `Cache-Control: no-store`.
`RunErrorHandler` nie zwraca treści wyjątków ani kodu źródłowego. Błąd parsowania
samego żądania jest zamieniany na 400 w kontrolerze; błąd parsera wykonawcy
pozostaje błędem serwera 500, zamiast obciążać poprawne żądanie klienta. Błędy mają
dokładnie pola `code`, `message`, `retryable`. Domyślny `SubmissionExecutor`
zgłasza `ExecutionUnavailableException`, mapowany na 503 z `Retry-After: 1`.
Atrapa wykonawcy jest podłączana jedynie przez konfigurację testową, bez
przełącznika uruchamiającego pozorne ocenianie w aplikacji.
`RunEndpointTests` testuje prawdziwe HTTP, kontroler i przypadek użycia z atrapą;
`RunEndpointUnavailableTests` potwierdza brak pozornego sukcesu zwykłej aplikacji.

Na tym etapie nie ma kompilacji ani wykonywania kodu uczestników, API Submit,
odpytywania zadań, endpointu capabilities, integracji Judge0, bazy danych ani
kolejki. Instrukcje budowania, testowania i uruchomienia są w głównym README.

Najpierw robimy cały flow dla Javy. C++ i Python dołożymy dopiero jak Java będzie działała od początku do końca.
