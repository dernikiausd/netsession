package de.sanniki.netsession

import java.io.BufferedReader
import java.io.InputStreamReader

private const val MAX_SERVICE_OUTPUT_CHARACTERS = 180_000

class NetSessionUserService : INetSessionUserService.Stub() {

    override fun runCommand(command: String): String {
        val process =
            ProcessBuilder(
                "/system/bin/sh",
                "-c",
                command
            )
                .redirectErrorStream(true)
                .start()

        val output =
            BufferedReader(
                InputStreamReader(process.inputStream)
            ).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8192)

                while (true) {
                    val count = reader.read(buffer)

                    if (count < 0) {
                        break
                    }

                    val remaining =
                        MAX_SERVICE_OUTPUT_CHARACTERS -
                            builder.length

                    if (remaining <= 0) {
                        builder.appendLine()
                        builder.appendLine(
                            "[Ausgabe nach " +
                                "$MAX_SERVICE_OUTPUT_CHARACTERS " +
                                "Zeichen gekürzt]"
                        )
                        break
                    }

                    builder.append(
                        buffer,
                        0,
                        minOf(count, remaining)
                    )
                }

                builder.toString()
            }

        val exitCode = process.waitFor()

        return buildString {
            appendLine("UserService-Prozess-Endcode: $exitCode")
            appendLine()
            append(
                output.ifBlank {
                    "Der Befehl lieferte keine Ausgabe."
                }
            )
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
