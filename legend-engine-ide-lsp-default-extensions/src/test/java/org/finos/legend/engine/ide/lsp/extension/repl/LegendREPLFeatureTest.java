/*
 * Copyright 2024 Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.legend.engine.ide.lsp.extension.repl;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class LegendREPLFeatureTest
{
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown()
    {
        this.executorService.shutdownNow();
    }

    @Test
    void replStarts() throws Exception
    {
        PipedInputStream replInput = new PipedInputStream();
        OutputStreamWriter replInputConsole = new OutputStreamWriter(new PipedOutputStream(replInput));

        PipedOutputStream replOutput = new PipedOutputStream();
        PipedInputStream replOutputConsole = new PipedInputStream(replOutput);

        Terminal terminalOverride = TerminalBuilder.builder()
                .streams(replInput, replOutput)
                .providers(TerminalBuilder.PROP_PROVIDER_EXEC)
                .build();
        TerminalBuilder.setTerminalOverride(terminalOverride);

        Future<?> replFuture = this.executorService.submit(new LegendREPLFeatureImpl()::startREPL);

        read(replFuture, replOutputConsole, "Ready!");

        sendREPLCommand(replInputConsole, "help");
        read(replFuture, replOutputConsole, "<pure expression>");

        sendREPLCommand(replInputConsole, "exit");
        // wait for repl to complete and exit
        replFuture.get(30, TimeUnit.SECONDS);
    }

    private static void sendREPLCommand(OutputStreamWriter replInputConsole, String help) throws IOException
    {
        replInputConsole.write(help + System.lineSeparator());
        replInputConsole.flush();
    }

    private static void read(Future<?> replFuture, PipedInputStream replOutputConsole, String untilToken) throws Exception
    {
        StringBuilder output = new StringBuilder();

        while (true)
        {
            if (replFuture.isDone())
            {
                // will throw if an exception happen
                replFuture.get();
                break;
            }

            int read = replOutputConsole.read();
            if (read != -1)
            {
                System.err.print((char) read);
                output.append((char) read);
            }
            else
            {
                break;
            }

            if (output.toString().contains(untilToken) && output.toString().endsWith(LineReaderImpl.BRACKETED_PASTE_ON + "> "))
            {
                break;
            }
        }
    }
}