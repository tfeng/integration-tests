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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenFactory;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.UnbufferedCharStream;
import org.antlr.v4.runtime.UnbufferedTokenStream;
import org.apache.log4j.Logger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.testng.Assert;
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
import com.google.common.io.Files;

@Test
public class TestJerseySources {

  private static final Logger LOG = Logger.getLogger(TestJerseySources.class);

  public void testJerseySources() throws InitializationException, URISyntaxException,
      ProcessingException {
    GitRepository repository =
        new GitRepository(new URI("https://github.com/jersey/jersey.git"),
            new GitConfig().setCommitRevision(
                ObjectId.fromString("ce83e9bc94e153a22ecd6917d6885c897e58d61e")).setProgressMonitor(
                    new TextProgressMonitor()));
    repository.process(new GitEntryProcessor() {
      @Override
      public void process(GitEntry entry) throws Exception {
        String extension = Files.getFileExtension(entry.getPath());
        if ("java".equals(extension)) {
          LOG.info("Parsing: " + entry.getPath());
          parseJava(entry.open().openStream());
        } else if ("properties".equals(extension)) {
          LOG.info("Parsing: " + entry.getPath());
          Properties properties = parseProperties(entry.open().openStream());

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
    JavaParser parser = new JavaParser(tokenStream);
    parser.setErrorHandler(new BailErrorStrategy());
    CompilationUnitContext context = parser.compilationUnit();

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
