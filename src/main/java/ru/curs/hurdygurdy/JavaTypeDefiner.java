package ru.curs.hurdygurdy;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Data;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaTypeDefiner extends TypeDefiner<TypeSpec> {
    private boolean hasJsonZonedDateTimeDeserializer;

    public JavaTypeDefiner(String rootPackage, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        super(rootPackage, typeSpecBiConsumer);
    }

    @Override
    public TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder parent) {
        @SuppressWarnings("LocalVariableName")
        String $ref = schema.get$ref();
        if ($ref == null) {
            String internalType = schema.getType();
            switch (internalType) {
                case "string":
                    if ("date".equals(schema.getFormat())) {
                        return TypeName.get(LocalDate.class);
                    } else if ("date-time".equals(schema.getFormat())) {
                        return TypeName.get(ZonedDateTime.class);
                    } else if ("uuid".equals(schema.getFormat())) {
                        return ClassName.get(UUID.class);
                    } else if (schema.getEnum() != null) {
                        //internal enum
                        String simpleName = schema.getTitle();
                        if (simpleName == null) {
                            throw new IllegalStateException("Inline enum schema must have a title");
                        }
                        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(simpleName).addModifiers(Modifier.PUBLIC);
                        for (Object e : schema.getEnum()) {
                            enumBuilder.addEnumConstant(e.toString());
                        }
                        TypeSpec internalEnum = enumBuilder.build();
                        parent.addType(internalEnum);

                        return ClassName.get("", simpleName);
                    } else return ClassName.get(String.class);
                case "number":
                    if ("float".equals(schema.getFormat())) {
                        return TypeName.FLOAT.box();
                    } else {
                        return TypeName.DOUBLE.box();
                    }
                case "integer":
                    if ("int64".equals(schema.getFormat())) {
                        return TypeName.LONG.box();
                    } else {
                        return TypeName.INT.box();
                    }
                case "boolean":
                    return TypeName.BOOLEAN;
                case "array":
                    Schema<?> itemsSchema = ((ArraySchema) schema).getItems();
                    return ParameterizedTypeName.get(ClassName.get(List.class),
                            defineJavaType(itemsSchema, openAPI, parent));
                case "object":
                default:
                    String simpleName = schema.getTitle();
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema, openAPI));
                        return ClassName.get(String.join(".", rootPackage, "dto"),
                                simpleName);
                    } else {
                        //This means failure, in fact.
                        return ClassName.OBJECT;
                    }
            }
        } else {
            Matcher matcher = Pattern.compile("/([^/$]+)$").matcher($ref);
            matcher.find();
            return ClassName.get(String.join(".", rootPackage, "dto"), matcher.group(1));
        }
    }

    private void ensureJsonZonedDateTimeDeserializer() {
        if (!hasJsonZonedDateTimeDeserializer) {
            TypeSpec typeSpec =
                    TypeSpec.classBuilder("ZonedDateTimeDeserializer")
                            .superclass(ParameterizedTypeName.get(
                                    ClassName.get(JsonDeserializer.class),
                                    ClassName.get(ZonedDateTime.class)
                            ))
                            .addModifiers(Modifier.PUBLIC)
                            .addField(FieldSpec.builder(ClassName.get(DateTimeFormatter.class),
                                    "formatter")
                                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                    .initializer("$T.ISO_OFFSET_DATE_TIME", DateTimeFormatter.class)
                                    .build())
                            .addMethod(MethodSpec.methodBuilder(
                                            "deserialize")
                                    .returns(ClassName.get(ZonedDateTime.class))
                                    .addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addParameter(ParameterSpec.builder(JsonParser.class, "jsonParser").build())
                                    .addParameter(ParameterSpec.builder(DeserializationContext.class,
                                            "deserializationContext").build())
                                    .addException(IOException.class)

                                    .addStatement("String date = jsonParser.getText()")
                                    .beginControlFlow("try ")
                                    .addStatement(
                                            "return $T.parse(date, formatter)", ZonedDateTime.class)
                                    .endControlFlow()
                                    .beginControlFlow("catch ($T e)", DateTimeException.class)
                                    .addStatement("throw new $T(jsonParser, e.getMessage())", JsonParseException.class)
                                    .endControlFlow()
                                    .build())
                            .build();
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec);
            hasJsonZonedDateTimeDeserializer = true;
        }
    }

    @Override
    TypeSpec getDTOClass(String name, Schema<?> schema, OpenAPI openAPI) {


        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(name)
                .addAnnotation(Data.class)
                .addAnnotation(AnnotationSpec.builder(JsonNaming.class).addMember("value",
                        "$T.class", ClassName.get(PropertyNamingStrategies.SnakeCaseStrategy.class)).build())
                .addModifiers(Modifier.PUBLIC);
        getExtendsList(schema).stream().map(ClassName::bestGuess).forEach(classBuilder::addSuperinterface);
        //Add properties
        Map<String, Schema> schemaMap = schema.getProperties();
        if (schemaMap != null) {
            for (Map.Entry<String, Schema> entry : schemaMap.entrySet()) {
                if (!entry.getKey().matches("[a-z][a-z_0-9]*")) throw new IllegalStateException(
                        String.format("Property '%s' of schema '%s' is not in snake case",
                                entry.getKey(), name)
                );
                TypeName typeName = defineJavaType(entry.getValue(), openAPI, classBuilder);
                FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                        typeName,
                        CaseUtils.snakeToCamel(entry.getKey()), Modifier.PRIVATE);
                if (typeName instanceof ClassName && "ZonedDateTime"
                        .equals(((ClassName) typeName).simpleName())) {
                    fieldBuilder.addAnnotation(AnnotationSpec.builder(
                                    ClassName.get(JsonDeserialize.class))
                            .addMember("using", "ZonedDateTimeDeserializer.class")
                            .build());
                    ensureJsonZonedDateTimeDeserializer();
                }
                FieldSpec fieldSpec = fieldBuilder.build();
                classBuilder.addField(fieldSpec);
            }
        }
        return classBuilder.build();
    }

    @Override
    TypeSpec getEnum(String name, Schema<?> schema, OpenAPI openAPI) {
        TypeSpec.Builder classBuilder = TypeSpec.enumBuilder(name).addModifiers(Modifier.PUBLIC);
        for (Object val : schema.getEnum()) {
            classBuilder.addEnumConstant(val.toString());
        }
        return classBuilder.build();
    }
}
