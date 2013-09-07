/**
 * Copyright 2013 Huining (Thomas) Feng (tfeng@berkeley.edu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bacoder.integration.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenFactory;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.UnbufferedCharStream;
import org.antlr.v4.runtime.UnbufferedTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.apache.log4j.Logger;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.util.FileUtils;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.bacoder.parser.java.JavaLexer;
import com.bacoder.parser.java.JavaParser;
import com.bacoder.parser.java.JavaParser.CompilationUnitContext;
import com.bacoder.parser.java.adapter.CompilationUnitAdapter;
import com.bacoder.parser.java.adapter.JavaAdapters;
import com.bacoder.parser.java.api.CompilationUnit;
import com.bacoder.parser.javaproperties.JavaPropertiesLexer;
import com.bacoder.parser.javaproperties.JavaPropertiesParser;
import com.bacoder.parser.javaproperties.JavaPropertiesParser.PropertiesContext;
import com.bacoder.parser.javaproperties.adapter.PropertiesAdapter;
import com.bacoder.parser.javaproperties.adapter.PropertiesAdapters;
import com.bacoder.parser.javaproperties.api.KeyValue;
import com.bacoder.parser.javaproperties.api.Properties;
import com.bacoder.scmtools.core.InitializationException;
import com.bacoder.scmtools.core.ProcessingException;
import com.bacoder.scmtools.git.GitConfig;
import com.bacoder.scmtools.git.GitEntry;
import com.bacoder.scmtools.git.GitEntryProcessor;
import com.bacoder.scmtools.git.GitRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

@Test
public class TestEclipseJDTSources {

  private static final List<String> EXPECTED_FAILING_FILES =
      ImmutableList.of(
          "org.eclipse.jdt.core.tests.model/workspace",
          "org.eclipse.jdt.core.tests.model/workspace/Formatter/test625/A_in.java");

  private static final Logger LOG = Logger.getLogger(TestEclipseJDTSources.class);

  private static final JavaParser parser = new JavaParser(null);

  private File gitRepository;

  @AfterTest
  public void cleanup() throws IOException {
    if (gitRepository != null) {
      FileUtils.delete(gitRepository, FileUtils.RECURSIVE);
      gitRepository = null;
    }
  }

  @BeforeTest
  public void setup() throws IOException {
    gitRepository = java.nio.file.Files.createTempDirectory("test-jdt").toFile();
    LOG.info("Using temporary directory " + gitRepository);
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          cleanup();
        } catch (IOException e) {
          LOG.error("Unable to delete temporary directory " + gitRepository, e);
        }
      }
    }));
  }

  public void testEclipseJDTSources() throws InitializationException, URISyntaxException,
      ProcessingException {
    GitRepository repository =
        new GitRepository(new URI("http://git.eclipse.org/gitroot/jdt/eclipse.jdt.core.git"),
            new GitConfig().setBranch("R4_3_maintenance").setProgressMonitor(
                new TextProgressMonitor()).setDirectory(gitRepository));
    repository.process(new GitEntryProcessor() {
      @Override
      public void process(GitEntry entry) throws Exception {
        String extension = Files.getFileExtension(entry.getPath());
        if ("java".equals(extension)) {
          LOG.info("Parsing: " + entry.getPath());
          ObjectLoader loader = entry.open();
          try {
            parseJava(loader.openStream());
          } catch (Throwable t) {
            boolean ignore = false;
            for (String prefix : EXPECTED_FAILING_FILES) {
              if (entry.getPath().startsWith(prefix)) {
                ignore = true;
              }
            }
            if (!ignore) {
              throw t;
            }
          }

        } else if ("properties".equals(extension)) {
          LOG.info("Parsing: " + entry.getPath());
          InputStream stream = entry.open().openStream();
          Properties properties = parseProperties(stream);

          java.util.Properties expected = new java.util.Properties();
          try {
            expected.load(entry.open().openStream());
          } catch (Exception e) {
            throw new RuntimeException("Unable to load properties" + e);
          }

          for (KeyValue keyValue : properties.getKeyValues()) {
            String key = keyValue.getKey().getSanitizedText();
            Assert.assertTrue(expected.containsKey(key),
                String.format("Key \"%s\" does not exist in expected Java properties", key));
            String value = keyValue.getValue().getSanitizedText();
            Assert.assertEquals(value, expected.get(key),
                String.format("Value \"%s\" does not match expected Java property value \"%s\"",
                    value, expected.get(key)));
          }
        }
      }
    });
  }

  private CompilationUnit parseJava(InputStream inputStream) throws IOException {
    CharStream stream = new UnbufferedCharStream(inputStream);
    JavaLexer lexer = new JavaLexer(stream);
    lexer.setTokenFactory(new CommonTokenFactory(true));
    UnbufferedTokenStream<Token> tokenStream = new UnbufferedTokenStream<Token>(lexer);
    parser.setInputStream(tokenStream);
    parser.setErrorHandler(new BailErrorStrategy());

    CompilationUnitContext context;
    try {
      parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
      context = parser.compilationUnit();
    } catch (RuntimeException e) {
      // Enable this if there are files that are LL-parsable but not SLL-parsable.
      /* if (e.getClass() == RuntimeException.class
          && (e.getCause() instanceof RecognitionException
              || e.getCause() instanceof InputMismatchException)) {
        tokenStream.reset(); // rewind input stream
        // back to standard listeners/handlers
        parser.addErrorListener(ConsoleErrorListener.INSTANCE);
        parser.setErrorHandler(new DefaultErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        context = parser.compilationUnit();
      } else {
        throw e;
      } */
      throw e;
    }

    CompilationUnitAdapter adapter = new CompilationUnitAdapter(new JavaAdapters());
    return adapter.adapt(context);
  }

  private Properties parseProperties(InputStream inputStream) throws IOException {
    CharStream stream = new UnbufferedCharStream(inputStream);
    JavaPropertiesLexer lexer = new JavaPropertiesLexer(stream);
    lexer.setTokenFactory(new CommonTokenFactory(true));
    UnbufferedTokenStream<Token> tokenStream = new UnbufferedTokenStream<Token>(lexer);
    JavaPropertiesParser parser = new JavaPropertiesParser(tokenStream);
    parser.setErrorHandler(new BailErrorStrategy());
    PropertiesContext context = parser.properties();

    PropertiesAdapter adapter = new PropertiesAdapter(new PropertiesAdapters());
    return adapter.adapt(context);
  }
}
