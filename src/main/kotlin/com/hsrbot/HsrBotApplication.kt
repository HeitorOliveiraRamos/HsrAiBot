package com.hsrbot

import com.hsrbot.config.BotProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [BotProperties::class])
class HsrBotApplication

fun main(args: Array<String>) {
    runApplication<HsrBotApplication>(*args)
}
