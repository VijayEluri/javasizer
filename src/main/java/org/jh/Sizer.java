/*
 * Copyright 2010 Jon Hoffman
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

package org.jh;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;

public class Sizer {

  static Instrumentation inst;

  public static void premain(String options, Instrumentation inst) {
    Sizer.inst = inst;
    System.out.println("Sizer Agent Configured.");
  }

  public static long shallowSize(Object object) {
    if (inst == null)
      throw new IllegalStateException("Instrumentation is null");
    if (object == null)
      return 0;

    return inst.getObjectSize(object);
  }

  interface SizeVisitor extends ObjectVisitor {
    long getSize();
  }

  private static int refSize() {
    String arch = System.getProperty("os.arch");
    if (arch == null) {
      System.err.println("WARNING Sizer: os.arch not set, assuming 64 bit");
      return 8;
    } else {
      System.out.println("Sizer detected architecture: " + arch);
      return arch.contains("64") ? 8 : 4;
    }
  }

  static abstract class CachingSizeVisitor implements SizeVisitor {
    private final Map<Class<?>, Long> sizeCache = new HashMap<Class<?>, Long>();

    abstract long calculateSize(Object obj, ClassInfo info);

    long shallowSize(Object obj, ClassInfo info) {
      Class<?> clazz = obj.getClass();
      Long size = sizeCache.get(clazz);
      if (size == null) {
        size = calculateSize(clazz, info);
        sizeCache.put(clazz, size);
      }
      return size;
    }
  }

  public static class ReflectionSizeVisitor extends CachingSizeVisitor {
    static int REFERENCE = refSize();
    static int OBJECT_OVEREHAD = REFERENCE * 2;
    static int ARRAY = REFERENCE;
    static int INT = 4;
    static int LONG = 8;
    static int BYTE = 1;
    static int BOOLEAN = 1;
    static int SHORT = 2;
    static int CHAR = 2;
    static int FLOAT = 4;
    static int DOUBLE = 8;

    private long size = 0;

    public long getSize() {
      return size;
    }

    private long sizeOfType(Class<?> type) {
      if (type == int.class) {
        return INT;
      } else if (type == long.class) {
        return LONG;
      } else if (type == byte.class) {
        return BYTE;
      } else if (type == boolean.class) {
        return BOOLEAN;
      } else if (type == char.class) {
        return CHAR;
      } else if (type == float.class) {
        return FLOAT;
      } else if (type == double.class) {
        return DOUBLE;
      } else if (type == short.class) {
        return SHORT;
      } else
        return REFERENCE;
    }

    /**
     * align to 8 byte boundaries. Not sure if this is correct behavior on all
     * JVMs
     * 
     * @param size
     * @return
     */
    private long align(long size) {
      long rem = size % 8;
      return size + ((rem == 0) ? 0 : 8 - rem);
    }

    public boolean visit(ClassInfo info, Object obj) {
      if (info.isArray) {
        size += align(OBJECT_OVEREHAD + ARRAY + Array.getLength(obj) * sizeOfType(obj.getClass().getComponentType()));
      } else {
        size += shallowSize(obj, info);
      }
      return true;
    }

    @Override
    long calculateSize(Object obj, ClassInfo info) {
      long tSize = OBJECT_OVEREHAD;
      for (FieldInfo f : info.fields) {
        tSize += sizeOfType(f.field.getType());
      }
      return align(tSize);
    }

  }

  public static class InstrumentationSizeVisitor implements SizeVisitor {
    long size = 0;

    public long getSize() {
      return size;
    }

    public boolean visit(ClassInfo info, Object obj) {
      size += Sizer.shallowSize(obj);
      return true;
    }
  }

  static class NoopVisitor implements ObjectVisitor {
    public boolean visit(ClassInfo info, Object obj) {
      return true;
    }
  }

  private static SizeVisitor createSizeVisitor() {
    if (inst == null)
      return new ReflectionSizeVisitor();
    else
      return new InstrumentationSizeVisitor();
  }

  public static long sizeof(Object o) {
    return sizeof(createSizeVisitor(), o);
  }

  public static long sizeof(SizeVisitor visitor, Object o) {
    GraphTraverser traverser = new GraphTraverser();
    traverser.traverse(visitor, o);
    return visitor.getSize();
  }

  public static long sizeof(Object base, Object measured) {
    GraphTraverser traverser = new GraphTraverser();
    NoopVisitor noop = new NoopVisitor();
    traverser.traverse(noop, base);
    SizeVisitor visitor = createSizeVisitor();
    traverser.traverse(visitor, measured);
    return visitor.getSize();
  }
}
