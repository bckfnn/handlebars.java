package com.github.edgarespina.handlebars;

import static org.parboiled.common.Preconditions.checkNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * A factory class for {@link Context}.
 *
 * @author edgar.espina
 * @since 0.1.0
 */
public final class ContextFactory {

  /**
   * Default implementation of {@link Context}.
   *
   * @author edgar.espina
   * @since 0.1.0
   */
  private static final class ContextImpl implements Context {

    /**
     * Strategy for method execution.
     *
     * @author edgar.espina
     * @since 0.1.0
     */
    interface MethodCallback {
      /**
       * Execute the method.
       *
       * @param method The method.
       * @return The method return value.
       * @throws Exception If the method cannot be executed.
       */
      Object doWith(Method method) throws Exception;
    }

    /**
     * Strategy for method lookup.
     *
     * @author edgar.espina
     * @since 0.1.0
     */
    interface MethodFilter {

      /**
       * Returns true if the method matches.
       *
       * @param method The candidate method.
       * @return True if the method matches.
       */
      boolean matches(Method method);
    }

    /**
     * Mark for fail context lookup.
     */
    private static final Object NULL = new Object();

    /**
     * The parent context. Optional.
     */
    private Context parent;

    /**
     * The target value. Resolved as '.' or 'this' inside templates. Required.
     */
    private final Object model;

    /**
     * A thread safe storage.
     */
    private final Map<String, Object> storage;

    /**
     * Creates a new context.
     *
     * @param parent The parent context. Optional.
     * @param target The target value. Resolved as '.' or 'this' inside
     *        templates. Required.
     */
    private ContextImpl(final Context parent, final Object target) {
      this.parent = parent;
      this.model = target;
      if (parent != null) {
        this.storage = ((ContextImpl) parent).storage;
      } else {
        this.storage = new HashMap<String, Object>();
        this.storage.put("partials", new HashMap<String, Template>());
      }
    }

    @Override
    public Map<String, Object> storage() {
      return storage;
    }

    @Override
    public Object model() {
      return model;
    }

    @Override
    public Object get(final Object key) {
      if (".".equals(key) || "this".equals(key)) {
        return model;
      }
      String[] path = toPath(key);
      Object value = get(path);
      if (value == null && parent != null) {
        value = parent.get(key);
      }
      return value == NULL ? null : value;
    }

    /**
     * Split the property name by '.' and create an array of it.
     *
     * @param key The property's name.
     * @return A path representation of the property (array based).
     */
    private String[] toPath(final Object key) {
      StringTokenizer tokenizer = new StringTokenizer(key.toString(), ".");
      String[] path = new String[tokenizer.countTokens()];
      int i = 0;
      while (tokenizer.hasMoreTokens()) {
        path[i++] = tokenizer.nextToken();
      }
      return path;
    }

    /**
     * Iterate over the qualified path and return a value. The value can be
     * null, {@link #NULL} or not null. If the value is 'null', the value isn't
     * present
     * and the lookup algorithm will searchin for the value in the parent
     * context.
     * If the value is {@value #NULL} the search must stop bc the context for
     * the
     * given path exists but there isn't a value there.
     * Finally, a not null value means
     *
     * @param path The qualified path.
     * @return The value inside the stack for the given path.
     */
    private Object get(final String[] path) {
      Object current = model;
      for (int i = 0; i < path.length - 1; i++) {
        current = get(current, path[i]);
        if (current == null) {
          return null;
        }
      }
      String name = path[path.length - 1];
      Object value = get(current, name);
      if (value == null && current != model) {
        // We're looking in the right scope, but the value isn't there
        // returns a custom mark to stop looking
        value = NULL;
      }
      return value;
    }

    /**
     * Do the actual lookup of an unqualify property name.
     *
     * @param current The target object.
     * @param name The property's name.
     * @return The associated value.
     */
    @SuppressWarnings("rawtypes")
    protected Object get(final Object current, final String name) {
      final Object value;
      if (current == null) {
        value = null;
      } else if (current instanceof Map) {
        value = ((Map) current).get(name);
      } else {
        value = invoke(current, name);
      }
      return value;
    }

    /**
     * Look for the property name in the object hierarchy and execute the
     * appropriated JavaBean method.
     *
     * @param current The target object.
     * @param property The property's name.
     * @return The associated value.
     */
    private static Object invoke(final Object current, final String property) {
      final String getMethod = javaBeanMethod("get", property);
      final String isMethod = javaBeanMethod("is", property);
      Object value = invoke(current.getClass(), new MethodCallback() {
        @Override
        public Object doWith(final Method method) throws Exception {
          return method.invoke(current);
        }
      }, new MethodFilter() {
        @Override
        public boolean matches(final Method method) {
          boolean isStatic = Modifier.isStatic(method.getModifiers());
          return !isStatic
              && (method.getName().equals(getMethod)
              || method.getName().equals(isMethod));
        }
      });
      return value;
    }

    /**
     * Convert the property's name to a JavaBean read method name.
     *
     * @param prefix The prefix: 'get' or 'is'.
     * @param name The unqualified property name.
     * @return The javaBean method name.
     */
    private static String
        javaBeanMethod(final String prefix, final String name) {
      StringBuilder buffer = new StringBuilder(prefix);
      buffer.append(name);
      buffer.setCharAt(prefix.length(), Character.toUpperCase(name.charAt(0)));
      return buffer.toString();
    }

    /**
     * Perform the given callback operation on all matching methods of the given
     * class and superclasses (or given interface and super-interfaces).
     * <p>
     * The same named method occurring on subclass and superclass will appear
     * twice, unless excluded by the specified {@link MethodFilter}.
     *
     * @param clazz class to start looking at
     * @param mc the callback to invoke for each method
     * @param mf the filter that determines the methods to apply the callback to
     * @return The method return value.
     */
    public static Object invoke(final Class<?> clazz,
        final MethodCallback mc, final MethodFilter mf) {
      // Keep backing up the inheritance hierarchy.
      Method[] methods = clazz.getDeclaredMethods();
      for (Method method : methods) {
        if (mf != null && !mf.matches(method)) {
          continue;
        }
        try {
          return mc.doWith(method);
        } catch (Exception ex) {
          throw new IllegalStateException(
              "Shouldn't be illegal to access method '" + method.getName()
                  + "': " + ex);
        }
      }
      if (clazz.getSuperclass() != null) {
        return invoke(clazz.getSuperclass(), mc, mf);
      } else if (clazz.isInterface()) {
        for (Class<?> superIfc : clazz.getInterfaces()) {
          return invoke(superIfc, mc, mf);
        }
      }
      return null;
    }

    @Override
    public String toString() {
      return model.toString();
    }
  }

  /**
   * Not allowed.
   */
  private ContextFactory() {
  }

  /**
   * Wrap the given value as a {@link Context contextual object}. If the object
   * is an {@link Context} the method return that. Otherwise, the value is
   * wrapped as a {@link Context}.
   *
   * @param candidate The candidate value. May be null.
   * @return A new context.
   */
  public static Context wrap(final Object candidate) {
    return wrapInternal(null, candidate);
  }

  /**
   * Wrap the given value as a {@link Context contextual object}. If the object
   * is an {@link Context} the method return that. Otherwise, the value is
   * wrapped as a {@link Context}.
   *
   * @param parent The parent context. Required.
   * @param candidate The candidate value. May be null.
   * @return A new context.
   */
  public static Context wrap(final Context parent, final Object candidate) {
    checkNotNull(parent, "The parent context is required.");
    return wrapInternal(parent, candidate);
  }

  /**
   * Creates a new context for the given value.
   *
   * @param parent The parent context. Required.
   * @param candidate The candidate value. May be null.
   * @return A new context.
   */
  private static Context wrapInternal(final Context parent,
      final Object candidate) {
    if (candidate instanceof Context) {
      return (Context) candidate;
    }
    return new ContextImpl(parent, candidate);
  }
}
