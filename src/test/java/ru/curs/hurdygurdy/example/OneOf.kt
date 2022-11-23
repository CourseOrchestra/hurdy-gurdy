package ru.curs.hurdygurdy.example

import com.fasterxml.jackson.`annotation`.JsonSubTypes
import com.fasterxml.jackson.`annotation`.JsonTypeInfo
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.`annotation`.JsonNaming

@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonSubTypes(
    JsonSubTypes.Type(Variant1::class),
    JsonSubTypes.Type(Variant2::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public interface OneOf
