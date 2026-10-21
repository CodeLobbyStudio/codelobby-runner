package pl.codelobby.runner.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** The JSON profile selects one of the explicitly supported metadata shapes. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "profile")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JavaExecution.class, name = "JAVA_MAIN"),
        @JsonSubTypes.Type(value = CppExecution.class, name = "CPP_MAIN"),
        @JsonSubTypes.Type(value = PythonExecution.class, name = "PYTHON_SCRIPT")
})
public sealed interface ExecutionProfile permits JavaExecution, CppExecution, PythonExecution {
}
