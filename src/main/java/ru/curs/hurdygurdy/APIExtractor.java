package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public abstract class APIExtractor<T, B> implements TypeSpecExtractor<T> {
    final TypeDefiner<T> typeDefiner;
    final boolean generateResponseParameter;

    abstract class BuilderHolder {
        final B builder;

        protected BuilderHolder(B builder) {
            this.builder = builder;
        }

        abstract T build();
    }

    protected APIExtractor(TypeDefiner<T> typeDefiner, boolean generateResponseParameter) {
        this.typeDefiner = typeDefiner;
        this.generateResponseParameter = generateResponseParameter;
    }

    public final void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        Paths paths = openAPI.getPaths();
        if (paths == null) return;
        BuilderHolder builderHolder = builder();
        for (Map.Entry<String, PathItem> stringPathItemEntry : paths.entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry : stringPathItemEntry.getValue().readOperationsMap().entrySet()) {
                buildMethod(openAPI, builderHolder.builder, stringPathItemEntry, operationEntry);
            }
        }
        typeSpecBiConsumer.accept(ClassCategory.CONTROLLER, builderHolder.build());
    }

    abstract BuilderHolder builder();

    abstract void buildMethod(OpenAPI openAPI, B classBuilder, Map.Entry<String, PathItem> stringPathItemEntry,
                              Map.Entry<PathItem.HttpMethod, Operation> operationEntry);

    static Optional<Content> getSuccessfulReply(Operation operation) {
        return operation.getResponses().entrySet().stream()
                .filter(r -> r.getKey().matches("2\\d\\d"))
                .map(r -> r.getValue().getContent())
                .filter(Objects::nonNull)
                .findFirst();
    }

    static Optional<Map.Entry<String, MediaType>> getMediaType(Content content) {
        return content.entrySet().stream().findFirst();
    }

    static Stream<Parameter> getParameterStream(PathItem path, Operation operation) {
        return Stream.concat(
                        Optional.ofNullable(path.getParameters()).stream(),
                        Optional.ofNullable(operation.getParameters()).stream())
                .flatMap(Collection::stream);
    }

}
