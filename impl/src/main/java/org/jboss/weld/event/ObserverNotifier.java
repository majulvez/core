/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.event;

import static org.jboss.weld.util.cache.LoadingCacheUtils.getCacheValue;
import static org.jboss.weld.util.reflection.Reflections.cast;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

import javax.enterprise.inject.spi.EventMetadata;
import javax.enterprise.inject.spi.ObserverMethod;

import org.jboss.weld.bootstrap.api.ServiceRegistry;
import org.jboss.weld.injection.ThreadLocalStack.ThreadLocalStackReference;
import org.jboss.weld.logging.UtilLogger;
import org.jboss.weld.resolution.QualifierInstance;
import org.jboss.weld.resolution.Resolvable;
import org.jboss.weld.resolution.ResolvableBuilder;
import org.jboss.weld.resolution.TypeSafeObserverResolver;
import org.jboss.weld.resources.SharedObjectCache;
import org.jboss.weld.transaction.spi.TransactionServices;
import org.jboss.weld.util.Observers;
import org.jboss.weld.util.Types;
import org.jboss.weld.util.reflection.Reflections;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Provides event-related operations such sa observer method resolution and event delivery.
 *
 *
 *
 * @author Jozef Hartinger
 * @author David Allen
 *
 */
public class ObserverNotifier {

    /**
     *
     * @param resolver
     * @param services
     * @param strict indicates whether event type should be performed or not
     * @return ObserverNotifier instance
     */
    public static ObserverNotifier of(String contextId, TypeSafeObserverResolver resolver, ServiceRegistry services, boolean strict) {
        if (services.contains(TransactionServices.class)) {
            return new TransactionalObserverNotifier(contextId, resolver, services, strict);
        } else {
            return new ObserverNotifier(resolver, services, strict);
        }
    }

    private static final RuntimeException NO_EXCEPTION_MARKER = new RuntimeException();

    private final TypeSafeObserverResolver resolver;
    private final SharedObjectCache sharedObjectCache;
    private final boolean strict;
    protected final CurrentEventMetadata currentEventMetadata;
    private final LoadingCache<Type, RuntimeException> eventTypeCheckCache;

    protected ObserverNotifier(TypeSafeObserverResolver resolver, ServiceRegistry services, boolean strict) {
        this.resolver = resolver;
        this.sharedObjectCache = services.get(SharedObjectCache.class);
        this.strict = strict;
        this.currentEventMetadata = services.get(CurrentEventMetadata.class);
        if (strict) {
            eventTypeCheckCache = CacheBuilder.newBuilder().build(new EventTypeCheck());
        } else {
            eventTypeCheckCache = null; // not necessary
        }
    }

    public <T> ResolvedObservers<T> resolveObserverMethods(Type eventType, Annotation... qualifiers) {
        checkEventObjectType(eventType);
        return this.<T>resolveObserverMethods(buildEventResolvable(eventType, qualifiers));
    }

    public <T> ResolvedObservers<T> resolveObserverMethods(Type eventType, Set<Annotation> qualifiers) {
        checkEventObjectType(eventType);
        return this.<T>resolveObserverMethods(buildEventResolvable(eventType, qualifiers));
    }

    public <T> ResolvedObservers<T> resolveObserverMethods(Resolvable resolvable) {
        return cast(resolver.resolve(resolvable, true));
    }

    public void fireEvent(Object event, EventMetadata metadata, Annotation... qualifiers) {
        fireEvent(event.getClass(), event, metadata, qualifiers);
    }

    public void fireEvent(Type eventType, Object event, Annotation... qualifiers) {
        fireEvent(eventType, event, null, qualifiers);
    }

    protected void fireEvent(Type eventType, Object event, EventMetadata metadata, Annotation... qualifiers) {
        checkEventObjectType(eventType);
        // we use the array of qualifiers for resolution so that we can catch duplicate qualifiers
        notify(resolveObserverMethods(buildEventResolvable(eventType, qualifiers)), event, metadata);
    }

    public void fireEvent(Object event, Resolvable resolvable) {
        checkEventObjectType(event);
        notify(resolveObserverMethods(resolvable), event, null);
    }

    protected Resolvable buildEventResolvable(Type eventType, Set<Annotation> qualifiers) {
        // We can always cache as this is only ever called by Weld where we avoid non-static inner classes for annotation literals
        Set<Type> typeClosure = sharedObjectCache.getTypeClosureHolder(eventType).get();
        return new ResolvableBuilder(resolver.getMetaAnnotationStore())
            .addTypes(typeClosure)
            .addType(Object.class)
            .addQualifiers(qualifiers)
            .addQualifierUnchecked(QualifierInstance.ANY)
            .create();
    }

    protected Resolvable buildEventResolvable(Type eventType, Annotation... qualifiers) {
        // We can always cache as this is only ever called by Weld where we avoid non-static inner classes for annotation literals
        return new ResolvableBuilder(resolver.getMetaAnnotationStore())
            .addTypes(sharedObjectCache.getTypeClosureHolder(eventType).get())
            .addType(Object.class)
            .addQualifiers(qualifiers)
            .addQualifierUnchecked(QualifierInstance.ANY)
            .create();
    }

    public void clear() {
        resolver.clear();
        if (eventTypeCheckCache != null) {
            eventTypeCheckCache.invalidateAll();
        }
    }

    protected void checkEventObjectType(Object event) {
        checkEventObjectType(event.getClass());
    }

    public void checkEventObjectType(Type eventType) {
        if (strict) {
            RuntimeException exception = getCacheValue(eventTypeCheckCache, eventType);
            if (exception != NO_EXCEPTION_MARKER) {
                throw exception;
            }
        }
    }

    private static class EventTypeCheck extends CacheLoader<Type, RuntimeException> {

        @Override
        public RuntimeException load(Type eventType) {
            Type resolvedType = Types.getCanonicalType(eventType);

            /*
             * If the runtime type of the event object contains a type variable, the container must throw an IllegalArgumentException.
             */
            if (Types.containsUnresolvedTypeVariableOrWildcard(resolvedType)) {
                return UtilLogger.LOG.typeParameterNotAllowedInEventType(eventType);
            }

            /*
             * If the runtime type of the event object is assignable to the type of a container lifecycle event, IllegalArgumentException
             * is thrown.
             */
            Class<?> resolvedClass = Reflections.getRawType(eventType);
            for (Class<?> containerEventType : Observers.CONTAINER_LIFECYCLE_EVENT_CANONICAL_SUPERTYPES) {
                if (containerEventType.isAssignableFrom(resolvedClass)) {
                    return UtilLogger.LOG.eventTypeNotAllowed(eventType);
                }
            }
            return NO_EXCEPTION_MARKER;
        }
    }

    public <T> void notify(ResolvedObservers<T> observers, T event, EventMetadata metadata) {
        if (!observers.isMetadataRequired()) {
            metadata = null;
        }
        notifySyncObservers(observers.getImmediateObservers(), event, metadata);
        notifyTransactionObservers(observers.getTransactionObservers(), event, metadata);
    }

    protected <T> void notifySyncObservers(Set<ObserverMethod<? super T>> observers, T event, EventMetadata metadata) {
        if (observers.isEmpty()) {
            return;
        }
        final ThreadLocalStackReference<EventMetadata> stack = currentEventMetadata.pushIfNotNull(metadata);
        try {
            for (ObserverMethod<? super T> observer : observers) {
                observer.notify(event);
            }
        } finally {
            stack.pop();
        }
    }

    protected <T> void notifyTransactionObservers(Set<ObserverMethod<? super T>> observers, T event, EventMetadata metadata) {
        notifySyncObservers(observers, event, metadata); // no transaction support
    }
}
