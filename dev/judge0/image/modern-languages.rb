# Preserve original language IDs. Apply the runner's trusted C++17 baseline.
cpp = @languages.find { |entry| entry[:id] == 54 }
expected_cpp = "/usr/local/gcc-9.2.0/bin/g++ %s main.cpp"
raise "Review C++17 profile for this base image" unless cpp && cpp[:compile_cmd] == expected_cpp
cpp[:name] = "C++17 (GCC 9.2.0)"
cpp[:compile_cmd] = "/usr/local/gcc-9.2.0/bin/g++ %s -std=c++17 main.cpp"

@languages += [
  {
    id: 1001,
    name: "Java (Temurin 21.0.13)",
    is_archived: false,
    source_file: "Main.java",
    compile_cmd: "/usr/local/bin/java-with-budget.sh javac -encoding UTF-8 %s Main.java",
    run_cmd: "/usr/local/bin/java-with-budget.sh java Main"
  },
  {
    id: 1002,
    name: "Python (3.12.16)",
    is_archived: false,
    source_file: "script.py",
    run_cmd: "/usr/local/codelobby/python3.12/bin/python3.12 -B script.py"
  }
]
