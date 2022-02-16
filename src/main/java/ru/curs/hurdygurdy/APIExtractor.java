package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class APIExtractor<T, B> implements TypeSpecExtractor<T> {
    final TypeDefiner<T> typeDefiner;
    final boolean generateApiInterface;
    private final boolean generateResponseParameter;

    private final Map<String, B> builders = new HashMap<>();
    private final Function<String, B> builderSupplier;
    private final Function<B, T> buildInvoker;

    protected APIExtractor(TypeDefiner<T> typeDefiner,
                           boolean generateResponseParameter,
                           boolean generateApiInterface,
                           Function<String, B> builderSupplier,
                           Function<B, T> buildInvoker) {
        this.typeDefiner = typeDefiner;
        this.generateResponseParameter = generateResponseParameter;
        this.generateApiInterface = generateApiInterface;
        this.builderSupplier = builderSupplier;
        this.buildInvoker = buildInvoker;
    }

    private B builder(String className) {
        return builders.computeIfAbsent(className, k -> builderSupplier.apply(className));
    }

    public final void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        Paths paths = openAPI.getPaths();
        if (paths == null) return;
        generateClass(openAPI, paths, "Controller", generateResponseParameter);
        builders.values().stream().map(buildInvoker).forEach(t ->
                typeSpecBiConsumer.accept(ClassCategory.CONTROLLER, t));
        if (generateApiInterface) {
            builders.clear();
            generateClass(openAPI, paths, "Api", false);
            builders.values().stream().map(buildInvoker).forEach(t ->
                    typeSpecBiConsumer.accept(ClassCategory.CONTROLLER, t));
        }
    }

    private void generateClass(OpenAPI openAPI, Paths paths, String name, boolean responseParameter) {
        for (Map.Entry<String, PathItem> stringPathItemEntry : paths.entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry
                    : stringPathItemEntry.getValue().readOperationsMap().entrySet()) {
                List<String> tags = operationEntry.getValue().getTags();
                String typeName = CaseUtils.snakeToCamel(tags != null && !tags.isEmpty() ? tags.get(0) : "", true)
                        + name;
                buildMethod(openAPI, builder(typeName), stringPathItemEntry,
                        operationEntry, responseParameter);
            }
        }
    }

    abstract void buildMethod(OpenAPI openAPI, B builder, Map.Entry<String, PathItem> stringPathItemEntry,
                              Map.Entry<PathItem.HttpMethod, Operation> operationEntry,
                              boolean generateResponseParameter);

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
