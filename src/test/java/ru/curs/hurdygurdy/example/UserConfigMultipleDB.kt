package ru.curs.hurdygurdy.example

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.`annotation`.JsonNaming
import kotlin.Long

@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy::class)
public data class UserConfigMultipleDB(
    public val id: Long,
)
