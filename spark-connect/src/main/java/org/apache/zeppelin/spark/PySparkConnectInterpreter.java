/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.spark;

import org.apache.commons.lang3.StringUtils;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.python.PythonInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * PySpark interpreter for Spark Connect.
 * Reuses the Java SparkSession from SparkConnectInterpreter (via Py4j)
 * so that the Python side uses the same 3.5.x-compatible client as the SQL interpreter.
 */
public class PySparkConnectInterpreter extends PythonInterpreter {

  private static final Logger LOGGER = LoggerFactory.getLogger(PySparkConnectInterpreter.class);

  private SparkConnectInterpreter sparkConnectInterpreter;
  private InterpreterContext curIntpContext;

  public PySparkConnectInterpreter(Properties property) {
    super(property);
    this.useBuiltinPy4j = true;
  }

  @Override
  public void open() throws InterpreterException {
    setProperty("zeppelin.python.useIPython",
        getProperty("zeppelin.pyspark.connect.useIPython", "true"));

    this.sparkConnectInterpreter =
        getInterpreterInTheSameSessionByClassName(SparkConnectInterpreter.class);

    // Ensure the Java SparkSession is ready before starting Python
    sparkConnectInterpreter.open();

    super.open();

    if (!useIPython()) {
      try {
        bootstrapInterpreter("python/zeppelin_sparkconnect.py");
      } catch (IOException e) {
        LOGGER.error("Fail to bootstrap spark connect", e);
        throw new InterpreterException("Fail to bootstrap spark connect", e);
      }
    }
  }

  @Override
  public void close() throws InterpreterException {
    LOGGER.info("Close PySparkConnectInterpreter");
    super.close();
  }

  @Override
  protected org.apache.zeppelin.python.IPythonInterpreter getIPythonInterpreter()
      throws InterpreterException {
    return getInterpreterInTheSameSessionByClassName(IPySparkConnectInterpreter.class, false);
  }

  @Override
  public org.apache.zeppelin.interpreter.InterpreterResult interpret(String st,
      InterpreterContext context) throws InterpreterException {
    curIntpContext = context;
    return super.interpret(st, context);
  }

  @Override
  protected void preCallPython(InterpreterContext context) {
    callPython(new PythonInterpretRequest(
        "intp.setInterpreterContextInPython()", false, false));
  }

  public void setInterpreterContextInPython() {
    InterpreterContext.set(curIntpContext);
  }

  @Override
  protected String getPythonExec() {
    String pythonExec = getProperty("zeppelin.python", "python");
    if (StringUtils.isNotBlank(pythonExec)) {
      return pythonExec;
    }
    return "python";
  }

  /**
   * Exposes the Java SparkSession to the Python process via Py4j gateway.
   * This is the same session used by SparkConnectSqlInterpreter.
   */
  public SparkSession getSparkSession() {
    if (sparkConnectInterpreter != null) {
      return sparkConnectInterpreter.getSparkSession();
    }
    return null;
  }

  public SparkConnectInterpreter getSparkConnectInterpreter() {
    return sparkConnectInterpreter;
  }

  public int getMaxResult() {
    if (sparkConnectInterpreter != null) {
      return sparkConnectInterpreter.getMaxResult();
    }
    return Integer.parseInt(getProperty("zeppelin.spark.maxResult", "1000"));
  }

  @SuppressWarnings("unchecked")
  public String formatDataFrame(Object df, int maxResult) {
    return SparkConnectUtils.showDataFrame((Dataset<Row>) df, maxResult);
  }
}
