package ru.curs.hurdygurdy.example

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.`annotation`.JsonNaming

@JsonNaming(value = PropertyNamingStrategies.SnakeCaseStrategy::class)
public data class UserConfig(
    public val userConfigSingleDB: UserConfigSingleDB? = null,
    public val userConfigMultipleDB: UserConfigMultipleDB? = null,
)
