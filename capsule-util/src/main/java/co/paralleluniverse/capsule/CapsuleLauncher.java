/*
 * Capsule
 * Copyright (c) 2014, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are licensed under the terms 
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package co.paralleluniverse.capsule;

import co.paralleluniverse.common.JarClassLoader;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Provides methods for loading, inspecting, and launching capsules.
 *
 * @author pron
 */
public final class CapsuleLauncher {
    private static final String CAPSULE_CLASS_NAME = "Capsule";
    private static final String OPT_JMX_REMOTE = "com.sun.management.jmxremote";
    private static final String ATTR_MAIN_CLASS = "Main-Class";
    private static final String PROP_USER_HOME = "user.home";
    private static final String PROP_OS_NAME = "os.name";
    private static final String CACHE_DEFAULT_NAME = "capsule";

    /**
     * Creates a new capsule
     *
     * @param jarFile  the capsule JAR
     * @param cacheDir the directory to use as cache
     * @return the capsule.
     */
    public static Capsule newCapsule(Path jarFile, Path cacheDir) {
        return newCapsule(jarFile, null, cacheDir, null);
    }

    /**
     * Creates a new capsule
     *
     * @param jarFile    the capsule JAR
     * @param wrappedJar a path to a capsule JAR that will be launched (wrapped) by the empty capsule in {@code jarFile}
     *                   or {@code null} if no wrapped capsule is wanted
     * @param cacheDir   the directory to use as cache
     * @return the capsule.
     */
    public static Capsule newCapsule(Path jarFile, Path wrappedJar, Path cacheDir) {
        return newCapsule(jarFile, wrappedJar, cacheDir, null);
    }

    /**
     * Creates a new capsule
     *
     * @param jarFile    the capsule JAR
     * @param wrappedJar a path to a capsule JAR that will be launched (wrapped) by the empty capsule in {@code jarFile}
     *                   or {@code null} if no wrapped capsule is wanted
     * @param cacheDir   the directory to use as cache
     * @param javaHomes  a map from Java version strings to their respective JVM installation paths
     * @return the capsule.
     */
    public static Capsule newCapsule(Path jarFile, Path wrappedJar, Path cacheDir, Map<String, Path> javaHomes) {
        try {
            final Manifest mf;
            try (JarInputStream jis = new JarInputStream(Files.newInputStream(jarFile))) {
                mf = jis.getManifest();
            }

            final ClassLoader cl = new JarClassLoader(jarFile, true);
            final Class<?> clazz = loadCapsuleClass(mf, cl);
            if (clazz == null)
                throw new RuntimeException(jarFile + " does not appear to be a valid capsule.");

            if (javaHomes != null)
                setJavaHomes(clazz, javaHomes);

            final Constructor<?> ctor = accessible(clazz.getDeclaredConstructor(Path.class, Path.class));
            final Object capsule = ctor.newInstance(jarFile, cacheDir);

            if (wrappedJar != null) {
                final Method setTarget = accessible(clazz.getDeclaredMethod("setTarget", Path.class));
                setTarget.invoke(capsule, wrappedJar);
            }

            return wrap(capsule);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not create capsule instance.", e);
        }
    }

    private static Class<?> loadCapsuleClass(Manifest mf, ClassLoader cl) {
        final String mainClass = mf.getMainAttributes() != null ? mf.getMainAttributes().getValue(ATTR_MAIN_CLASS) : null;
        if (mainClass == null)
            return null;

        try {
            Class<?> clazz = cl.loadClass(mainClass);
            if (!isCapsuleClass(clazz))
                clazz = null;
            return clazz;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean isCapsuleClass(Class<?> clazz) {
        if (clazz == null)
            return false;
        if (CAPSULE_CLASS_NAME.equals(clazz.getName()))
            return true;
        return isCapsuleClass(clazz.getSuperclass());
    }

    private static Capsule wrap(Object capsule) {
        return (Capsule) Proxy.newProxyInstance(CapsuleLauncher.class.getClassLoader(), new Class<?>[]{Capsule.class}, new CapsuleAccess(capsule));
    }

    private static class CapsuleAccess implements InvocationHandler {
        private final Object capsule;
        private final Class<?> clazz;

        public CapsuleAccess(Object capsule) {
            this.capsule = capsule;
            this.clazz = capsule.getClass();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass().equals(Object.class) && method.getName().equals("equals")) {
                final Object other = args[0];
                if (other == this)
                    return true;
                if (!(other instanceof CapsuleAccess))
                    return false;
                return call(method, args);
            }

            try {
                return call(method, args);
            } catch (NoSuchMethodException e) {
                switch (method.getName()) {
                    case "getVersion":
                        return get("VERSION");

                    case "getModes":
                        return Collections.emptySet();

                    default:
                        throw new UnsupportedOperationException("Capsule " + clazz + " does not support this operation");
                }
            }
        }

        private Object call(Method method, Object[] args) throws NoSuchMethodException {
            final Method m = getMethod(clazz, method.getName(), method.getParameterTypes());
            if (m == null)
                throw new NoSuchMethodException();
            return CapsuleLauncher.invoke(capsule, m, args);
        }

        private Object get(String field) {
            final Field f = getField(clazz, field);
            if (f == null)
                throw new UnsupportedOperationException("Capsule " + clazz + " does not contain the field " + field);
            return CapsuleLauncher.get(capsule, f);
        }
    }

    /**
     * Returns all known Java installations
     *
     * @return a map from the version strings to their respective paths of the Java installations.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Path> getJavaHomes() {
        try {
            return (Map<String, Path>) accessible(Class.forName(CAPSULE_CLASS_NAME).getDeclaredMethod("getJavaHomes")).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setJavaHomes(Class<?> capsuleClass, Map<String, Path> javaHomes) {
        final Field homes = getField(capsuleClass, "JAVA_HOMES");
        if (homes == null)
            return;
        set(null, homes, javaHomes);
    }

    /**
     * Adds an option to the JVM arguments to enable JMX connection
     *
     * @param jvmArgs the JVM args
     * @return a new list of JVM args
     */
    public static List<String> enableJMX(List<String> jvmArgs) {
        final String arg = "-D" + OPT_JMX_REMOTE;
        if (jvmArgs.contains(arg))
            return jvmArgs;
        final List<String> cmdLine2 = new ArrayList<>(jvmArgs);
        cmdLine2.add(arg);
        return cmdLine2;
    }

    /**
     * Returns the path of the default capsule cache directory
     */
    public static Path getDefaultCacheDir() {
        final Path cache;
        cache = getCacheHome().resolve((isWindows() ? "" : ".") + CACHE_DEFAULT_NAME);
        return cache;
    }

    private static Path getCacheHome() {
        final Path userHome = Paths.get(System.getProperty(PROP_USER_HOME));
        if (!isWindows())
            return userHome;

        Path localData;
        final String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            localData = Paths.get(localAppData);
            if (!Files.isDirectory(localData))
                throw new RuntimeException("%LOCALAPPDATA% set to nonexistent directory " + localData);
        } else {
            localData = userHome.resolve(Paths.get("AppData", "Local"));
            if (!Files.isDirectory(localData))
                localData = userHome.resolve(Paths.get("Local Settings", "Application Data"));
            if (!Files.isDirectory(localData))
                throw new RuntimeException("%LOCALAPPDATA% is undefined, and neither "
                        + userHome.resolve(Paths.get("AppData", "Local")) + " nor "
                        + userHome.resolve(Paths.get("Local Settings", "Application Data")) + " have been found");
        }
        return localData;
    }

    private static boolean isWindows() {
        return System.getProperty(PROP_OS_NAME).toLowerCase().startsWith("windows");
    }

    //<editor-fold defaultstate="collapsed" desc="Reflection">
    /////////// Reflection ///////////////////////////////////
    private static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return getMethod0(clazz, name, paramTypes);
        }
    }

    private static Method getMethod0(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return accessible(clazz.getDeclaredMethod(name, paramTypes));
        } catch (NoSuchMethodException e) {
            return clazz.getSuperclass() != null ? getMethod0(clazz.getSuperclass(), name, paramTypes) : null;
        }
    }

    private static Field getField(Class<?> clazz, String name) {
        try {
            return accessible(clazz.getDeclaredField(name));
        } catch (NoSuchFieldException e) {
            return clazz.getSuperclass() != null ? getField(clazz.getSuperclass(), name) : null;
        }
    }

    private static Object invoke(Object obj, Method method, Object... params) {
        try {
            return method.invoke(obj, params);
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        } catch (InvocationTargetException e) {
            final Throwable t = e.getTargetException();
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            if (t instanceof Error)
                throw (Error) t;
            throw new RuntimeException(t);
        }
    }

    private static <T extends AccessibleObject> T accessible(T x) {
        if (!x.isAccessible())
            x.setAccessible(true);
        return x;
    }

    private static Object get(Object obj, Field field) {
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        }
    }

    private static void set(Object obj, Field field, Object value) {
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        }
    }
    //</editor-fold>

    private CapsuleLauncher() {
    }
}
