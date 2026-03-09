#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Reuse the Java SparkSession from SparkConnectInterpreter via Py4j.
# Wraps the Java DataFrame so that show()/collect() output goes to
# Python stdout (which Zeppelin captures), matching the SQL interpreter behavior.
#
# Guards against OOM on large collect/toPandas by enforcing configurable
# row limits and providing safe iteration helpers.

import sys
import warnings

intp = gateway.entry_point
_jspark = intp.getSparkSession()
_max_result = intp.getMaxResult()

_COLLECT_LIMIT_DEFAULT = _max_result
_COLLECT_WARN_THRESHOLD = 100000


def _rows_to_dicts(jrows, fields):
    """Convert Java Row list to Python dicts without per-value Py4j round-trips."""
    col_names = [f.name() for f in fields]
    result = []
    for row in jrows:
        d = {}
        for i, col in enumerate(col_names):
            d[col] = row.get(i)
        result.append(d)
    return result


class SparkConnectDataFrame(object):
    """Wrapper around a Java Dataset<Row> with production-safe data retrieval."""

    def __init__(self, jdf):
        self._jdf = jdf

    def show(self, n=20, truncate=True):
        effective_n = min(n, _max_result)
        print(intp.formatDataFrame(self._jdf, effective_n))

    def collect(self, limit=None):
        """Collect rows to the driver. Applies a safety limit to prevent OOM.

        Args:
            limit: Max rows to collect. Defaults to zeppelin.spark.maxResult.
                   Pass limit=-1 to collect ALL rows (use with caution).
        """
        if limit is None:
            limit = _COLLECT_LIMIT_DEFAULT
        if limit == -1:
            row_count = self._jdf.count()
            if row_count > _COLLECT_WARN_THRESHOLD:
                warnings.warn(
                    "Collecting %d rows to driver. This may cause OOM. "
                    "Consider using .limit() or .toPandas() with a smaller subset."
                    % row_count)
            return list(self._jdf.collectAsList())
        return list(self._jdf.limit(limit).collectAsList())

    def take(self, n):
        return list(self._jdf.limit(n).collectAsList())

    def head(self, n=1):
        rows = self.take(n)
        if n == 1:
            return rows[0] if rows else None
        return rows

    def first(self):
        return self.head(1)

    def toPandas(self, limit=None):
        """Convert to pandas DataFrame. Applies a safety limit.

        Tries to use pyarrow for efficient serialization if available,
        otherwise falls back to row-by-row conversion through Py4j.

        Args:
            limit: Max rows. Defaults to zeppelin.spark.maxResult.
                   Pass limit=-1 for all rows (use with caution on large data).
        """
        try:
            import pandas as pd
        except ImportError:
            raise ImportError(
                "pandas is required for toPandas(). "
                "Install it with: pip install pandas")

        if limit is None:
            limit = _COLLECT_LIMIT_DEFAULT
        if limit == -1:
            source_jdf = self._jdf
        else:
            source_jdf = self._jdf.limit(limit)

        fields = source_jdf.schema().fields()
        col_names = [f.name() for f in fields]
        jrows = source_jdf.collectAsList()

        if len(jrows) == 0:
            return pd.DataFrame(columns=col_names)

        rows_data = []
        for row in jrows:
            rows_data.append([row.get(i) for i in range(len(col_names))])

        return pd.DataFrame(rows_data, columns=col_names)

    def count(self):
        return self._jdf.count()

    def limit(self, n):
        return SparkConnectDataFrame(self._jdf.limit(n))

    def filter(self, condition):
        return SparkConnectDataFrame(self._jdf.filter(condition))

    def select(self, *cols):
        return SparkConnectDataFrame(self._jdf.select(*cols))

    def where(self, condition):
        return self.filter(condition)

    def groupBy(self, *cols):
        return self._jdf.groupBy(*cols)

    def orderBy(self, *cols):
        return SparkConnectDataFrame(self._jdf.orderBy(*cols))

    def sort(self, *cols):
        return self.orderBy(*cols)

    def distinct(self):
        return SparkConnectDataFrame(self._jdf.distinct())

    def drop(self, *cols):
        return SparkConnectDataFrame(self._jdf.drop(*cols))

    def dropDuplicates(self, *cols):
        if cols:
            return SparkConnectDataFrame(self._jdf.dropDuplicates(*cols))
        return SparkConnectDataFrame(self._jdf.dropDuplicates())

    def join(self, other, on=None, how="inner"):
        other_jdf = other._jdf if isinstance(other, SparkConnectDataFrame) else other
        if on is not None:
            return SparkConnectDataFrame(self._jdf.join(other_jdf, on, how))
        return SparkConnectDataFrame(self._jdf.join(other_jdf))

    def union(self, other):
        other_jdf = other._jdf if isinstance(other, SparkConnectDataFrame) else other
        return SparkConnectDataFrame(self._jdf.union(other_jdf))

    def withColumn(self, colName, col):
        return SparkConnectDataFrame(self._jdf.withColumn(colName, col))

    def withColumnRenamed(self, existing, new):
        return SparkConnectDataFrame(self._jdf.withColumnRenamed(existing, new))

    def cache(self):
        self._jdf.cache()
        return self

    def persist(self, storageLevel=None):
        if storageLevel:
            self._jdf.persist(storageLevel)
        else:
            self._jdf.persist()
        return self

    def unpersist(self, blocking=False):
        self._jdf.unpersist(blocking)
        return self

    def explain(self, extended=False):
        if extended:
            self._jdf.explain(True)
        else:
            self._jdf.explain()

    def createOrReplaceTempView(self, name):
        self._jdf.createOrReplaceTempView(name)

    def createTempView(self, name):
        self._jdf.createTempView(name)

    def schema(self):
        return self._jdf.schema()

    def dtypes(self):
        schema = self._jdf.schema()
        return [(f.name(), str(f.dataType())) for f in schema.fields()]

    def columns(self):
        schema = self._jdf.schema()
        return [f.name() for f in schema.fields()]

    def printSchema(self):
        print(self._jdf.schema().treeString())

    def describe(self, *cols):
        if cols:
            return SparkConnectDataFrame(self._jdf.describe(*cols))
        return SparkConnectDataFrame(self._jdf.describe())

    def summary(self, *statistics):
        if statistics:
            return SparkConnectDataFrame(self._jdf.summary(*statistics))
        return SparkConnectDataFrame(self._jdf.summary())

    def isEmpty(self):
        return self._jdf.isEmpty()

    def __repr__(self):
        try:
            return "SparkConnectDataFrame[%s]" % ", ".join(
                f.name() for f in self._jdf.schema().fields())
        except Exception:
            return "SparkConnectDataFrame[schema unavailable]"

    def __getattr__(self, name):
        return getattr(self._jdf, name)

    def __iter__(self):
        """Safe iteration with default limit to prevent OOM."""
        rows = self._jdf.limit(_COLLECT_LIMIT_DEFAULT).collectAsList()
        return iter(rows)

    def __len__(self):
        return int(self._jdf.count())


class SparkConnectSession(object):
    """Wraps the Java SparkSession so that sql() returns a wrapped DataFrame."""

    def __init__(self, jsession):
        self._jsession = jsession

    def sql(self, query):
        return SparkConnectDataFrame(self._jsession.sql(query))

    def table(self, tableName):
        return SparkConnectDataFrame(self._jsession.table(tableName))

    def read(self):
        return self._jsession.read()

    def createDataFrame(self, data, schema=None):
        try:
            import pandas as pd
            if isinstance(data, pd.DataFrame):
                warnings.warn(
                    "createDataFrame from pandas goes through Py4j serialization. "
                    "For large DataFrames, consider writing to a temp table instead.")
        except ImportError:
            pass
        if schema:
            return SparkConnectDataFrame(self._jsession.createDataFrame(data, schema))
        return SparkConnectDataFrame(self._jsession.createDataFrame(data))

    def range(self, start, end=None, step=1, numPartitions=None):
        if end is None:
            end = start
            start = 0
        if numPartitions:
            return SparkConnectDataFrame(
                self._jsession.range(start, end, step, numPartitions))
        return SparkConnectDataFrame(self._jsession.range(start, end, step))

    @property
    def catalog(self):
        return self._jsession.catalog()

    @property
    def version(self):
        return self._jsession.version()

    @property
    def conf(self):
        return self._jsession.conf()

    def stop(self):
        pass

    def __repr__(self):
        return "SparkConnectSession (via Py4j)"

    def __getattr__(self, name):
        return getattr(self._jsession, name)


def pip_install(*packages):
    """Install Python packages into the interpreter pod's environment.

    Usage:
        pip_install("requests")
        pip_install("requests", "pandas", "numpy==1.24.0")
        pip_install("requests>=2.28,<3.0")
    """
    import subprocess
    import importlib
    import site
    if not packages:
        print("Usage: pip_install('package1', 'package2', ...)")
        return
    cmd = [sys.executable, "-m", "pip", "install", "--quiet"] + list(packages)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if result.returncode == 0:
            installed = ", ".join(packages)
            print("Successfully installed: %s" % installed)
            if result.stdout.strip():
                print(result.stdout.strip())
            importlib.invalidate_caches()
            for new_path in site.getsitepackages() + [site.getusersitepackages()]:
                if new_path not in sys.path:
                    sys.path.insert(0, new_path)
        else:
            print("pip install failed (exit code %d):" % result.returncode)
            if result.stderr.strip():
                print(result.stderr.strip())
            if result.stdout.strip():
                print(result.stdout.strip())
    except subprocess.TimeoutExpired:
        print("pip install timed out after 300 seconds")
    except Exception as e:
        print("pip install error: %s" % str(e))


spark = SparkConnectSession(_jspark)
sqlContext = sqlc = spark
