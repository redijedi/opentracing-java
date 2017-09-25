/*
 * Copyright 2016-2017 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.util;

import io.opentracing.ActiveSpan;
import io.opentracing.noop.NoopTracer;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global tracer that forwards all methods to another tracer that can be
 * configured by calling {@link #register(Tracer)}.
 *
 * <p>
 * The {@linkplain #register(Tracer) register} method should only be called once
 * during the application initialization phase.<br>
 * If the {@linkplain #register(Tracer) register} method is never called,
 * the default {@link NoopTracer} is used.
 *
 * <p>
 * Where possible, use some form of dependency injection (of which there are
 * many) to access the `Tracer` instance. For vanilla application code, this is
 * often reasonable and cleaner for all of the usual DI reasons.
 *
 * <p>
 * That said, instrumentation for packages that are themselves statically
 * configured (e.g., JDBC drivers) may be unable to make use of said DI
 * mechanisms for {@link Tracer} access, and as such they should fall back on
 * {@link GlobalTracer}. By and large, OpenTracing instrumentation should
 * always allow the programmer to specify a {@link Tracer} instance to use for
 * instrumentation, though the {@link GlobalTracer} is a reasonable fallback or
 * default value.
 */
public final class GlobalTracer implements Tracer {
    private static final Logger LOGGER = Logger.getLogger(GlobalTracer.class.getName());

    /**
     * Singleton instance.
     * <p>
     * Since we cannot prevent people using {@linkplain #get() GlobalTracer.get()} as a constant,
     * this guarantees that references obtained before, during or after initialization
     * all behave as if obtained <em>after</em> initialization once properly initialized.<br>
     * As a minor additional benefit it makes it harder to circumvent the {@link Tracer} API.
     */
    private static final GlobalTracer INSTANCE = new GlobalTracer();

    /**
     * The registered {@link Tracer} delegate or the {@link NoopTracer} if none was registered yet.
     * Never {@code null}.
     */
    private static volatile Tracer tracer = NoopTracerFactory.create();

    private GlobalTracer() {
    }

    /**
     * Returns the constant {@linkplain GlobalTracer}.
     * <p>
     * All methods are forwarded to the currently configured tracer.<br>
     * Until a tracer is {@link #register(Tracer) explicitly configured},
     * the {@link io.opentracing.noop.NoopTracer NoopTracer} is used.
     *
     * @return The global tracer constant.
     * @see #register(Tracer)
     */
    public static Tracer get() {
        return INSTANCE;
    }

    /**
     * Register a {@link Tracer} to back the behaviour of the {@link #get() global tracer}.
     * <p>
     * Registration is a one-time operation, attempting to call it more often will result in a runtime exception.
     * <p>
     * Every application intending to use the global tracer is responsible for registering it once
     * during its initialization.
     *
     * @param tracer Tracer to use as global tracer.
     * @throws RuntimeException if there is already a current tracer registered
     */
    public static synchronized void register(final Tracer tracer) {
        if (tracer == null) {
            throw new NullPointerException("Cannot register GlobalTracer <null>.");
        }
        if (tracer instanceof GlobalTracer) {
            LOGGER.log(Level.FINE, "Attempted to register the GlobalTracer as delegate of itself.");
            return; // no-op
        }
        if (isRegistered() && !GlobalTracer.tracer.equals(tracer)) {
            throw new IllegalStateException("There is already a current global Tracer registered.");
        }
        GlobalTracer.tracer = tracer;
    }

    /**
     * Identify whether a {@link Tracer} has previously been registered.
     * <p>
     * This check is useful in scenarios where more than one component may be responsible
     * for registering a tracer. For example, when using a Java Agent, it will need to determine
     * if the application has already registered a tracer, and if not attempt to resolve and
     * register one itself.
     *
     * @return Whether a tracer has been registered
     */
    public static synchronized boolean isRegistered() {
        return !(GlobalTracer.tracer instanceof NoopTracer);
    }

    /**
     * Retrieves the {@link GlobalTracer} constant if one has been backed by an implementation or optionally registers, and returns, a {@link Tracer} 
     * via the supplier provided if there has been no backing implementation registered.
     * <p>
     * This method is provided to help prevent race conditions that can take place when calling {@link #isRegistered()} as a condition before calling 
     * {@link #register(Tracer)}. While these methods are themselves synchronized there is a space for a race condition to occur when doing so between
     * threads. Therefore, in the worst case it will add overhead for synchronization on the class. However the average case would only incur the cost
     * of calling {@link #isRegistered()} before returning an instance.
     *
     * @param tracerSupplier
     * @return The global tracer constant with backing implementation.
     */
    public static Tracer getOrRegister(TracerSupplier tracerSupplier) {
        if (!isRegistered()) {
            synchronized (GlobalTracer.class) {
                Tracer tempTracer = get(); // force read
                if (!isRegistered()) {
                    tempTracer = tracerSupplier.get();
                    register(tempTracer);
                }
            }
        }
        return get();
    }

    @Override
    public SpanBuilder buildSpan(String operationName) {
        return tracer.buildSpan(operationName);
    }

    @Override
    public <C> void inject(SpanContext spanContext, Format<C> format, C carrier) {
        tracer.inject(spanContext, format, carrier);
    }

    @Override
    public <C> SpanContext extract(Format<C> format, C carrier) {
        return tracer.extract(format, carrier);
    }

    @Override
    public String toString() {
        return GlobalTracer.class.getSimpleName() + '{' + tracer + '}';
    }

    @Override
    public ActiveSpan activeSpan() {
        return tracer.activeSpan();
    }

    @Override
    public ActiveSpan makeActive(Span span) {
        return tracer.makeActive(span);
    }

    /**
     * This interface provides a simply means of deferring the retrieval of a {@link Tracer} instance for the 
     * {@link GlobalTracer#getOrRegister(TracerSupplier)} until it can be determined whether an instance has been registered yet.
     * <p>
     */
    /*
     * Developer note: This is a single-method interface to allow for support in Java 8+ environments. The @FunctionalInterface annotation was omitted
     * to allow for backwards compatibility, but it should be noted that adding additional methods would defeat its lambda usage.
     */
    public interface TracerSupplier {

        /**
         * @return A tracer implementation to use as a backing instance for the {@link GlobalTracer}.
         */
        Tracer get();

    }

}
