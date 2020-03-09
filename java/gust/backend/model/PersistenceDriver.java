package gust.backend.model;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Message;
import gust.backend.runtime.Logging;
import gust.backend.runtime.ReactiveFuture;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import tools.elide.core.DatapointType;
import tools.elide.core.FieldType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.String.format;
import static gust.backend.model.ModelMetadata.*;


/**
 * Describes the surface of a generic persistence driver, which is capable of accepting arbitrary structured and typed
 * business data (also called "data models"), and managing them with regard to persistent storage, which includes
 * storing them when asked, and recalling them when subsequently asked to do so.
 *
 * <p>Persistence driver implementations do not always guarantee <i>durability</i> of data. For example,
 * {@link CacheDriver} implementations are also {@link PersistenceDriver}s, and that entire class of implementations
 * does not guarantee data will be there when you ask for it <i>at all</i> (relying on cache state is generally
 * considered to be a very bad practice).</p>
 *
 * <p>Other implementation trees exist (notably, {@link DatabaseDriver}) which go the other way, and are expected to
 * guarantee durability of data across restarts, distributed systems and networks, and failure cases, as applicable.
 * Database driver implementations also support richer data storage features like querying and indexing.</p>
 *
 * @see CacheDriver <pre>`CacheDriver`</pre> for persistence drivers with volatile durability guarantees
 * @see DatabaseDriver <pre>`DatabaseDriver`</pre> for drivers with rich features and/or strong durability guarantees.
 * @param <Key> Key record type (must be annotated with model role {@code OBJECT_KEY}).
 * @param <Model> Message/model type which this persistence driver is specialized for.
 * @param <Intermediate> Intermediate record format used by the underlying driver implementation during serialization.
 */
@Immutable
@ThreadSafe
@SuppressWarnings({"unused", "UnstableApiUsage"})
public interface PersistenceDriver<Key extends Message, Model extends Message, Intermediate> {
  /** Default timeout to apply when otherwise unspecified. */
  long DEFAULT_TIMEOUT = 30;

  /** Time units for {@link #DEFAULT_TIMEOUT}. */
  TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.SECONDS;

  /** Default timeout to apply when fetching from the cache. */
  long DEFAULT_CACHE_TIMEOUT = 5;

  /** Time units for {@link #DEFAULT_CACHE_TIMEOUT}. */
  TimeUnit DEFAULT_CACHE_TIMEOUT_UNIT = TimeUnit.SECONDS;

  /** Default model adapter internals. */
  @SuppressWarnings("SameParameterValue")
  final class Internals {
    /** Log pipe for default model adapter. */
    static final Logger logging = Logging.logger(PersistenceDriver.class);

    private Internals() { /* Disallow instantiation. */ }

    /** Runnable that might throw async exceptions. */
    @FunctionalInterface
    interface DriverRunnable {
      /**
       * Run some operation that may throw async-style exceptions.
       *
       * @throws TimeoutException The operation timed out.
       * @throws InterruptedException The operation was interrupted during execution.
       * @throws ExecutionException An execution error halted async execution.
       */
      void run() throws TimeoutException, InterruptedException, ExecutionException;
    }

    /**
     * Swallow any exceptions that occur
     *
     * @param operation Operation to run and wrap.
     */
    static void swallowExceptions(@Nonnull DriverRunnable operation) {
      try {
        operation.run();

      } catch (Exception exc) {
        Throwable inner = exc.getCause() != null ? exc.getCause() : exc;
        logging.warn(format(
          "Encountered unidentified exception '%s'. Message: '%s'.",
          exc.getClass().getSimpleName(), exc.getMessage()));

      }
    }

    /**
     * Convert async exceptions into persistence layer exceptions, according to the failure that occurred. Also print a
     * descriptive log statement.
     *
     * @param operation Operation to execute and wrap with protection.
     * @param <R> Return type for the callable operation, if applicable.
     * @return Return value of the async operation.
     */
    @CanIgnoreReturnValue
    static <R> R convertAsyncExceptions(@Nonnull Callable<R> operation) {
      try {
        return operation.call();
      } catch (InterruptedException ixe) {
        logging.warn(format("Interrupted. Message: '%s'.",
          ixe.getMessage()));
        throw PersistenceOperationFailed.forErr(PersistenceFailure.INTERRUPTED);

      } catch (ExecutionException exe) {
        Throwable inner = exe.getCause() != null ? exe.getCause() : exe;
        logging.warn(format("Encountered async exception '%s'. Message: '%s'.",
          inner.getClass().getSimpleName(), inner.getMessage()));
        throw PersistenceOperationFailed.forErr(PersistenceFailure.INTERNAL);

      } catch (TimeoutException txe) {
        throw PersistenceOperationFailed.forErr(PersistenceFailure.TIMEOUT);
      } catch (Exception exc) {
        logging.warn(format(
          "Encountered unidentified exception '%s'. Message: '%s'.",
          exc.getClass().getSimpleName(), exc.getMessage()));
        throw PersistenceOperationFailed.forErr(PersistenceFailure.INTERNAL,
          exc.getCause() != null ? exc.getCause() : exc);

      }
    }
  }

  // -- API: Execution -- //
  /**
   * Resolve an executor service for use with this persistence driver. Operations will be executed against this as they
   * are received.
   *
   * @return Scheduled executor service.
   */
  @Nonnull ListeningScheduledExecutorService executorService();

  // -- API: Codec -- //
  /**
   * Acquire an instance of the codec used by this adapter. Codecs are either injected/otherwise provided during adapter
   * construction, or they are specified statically if the adapter depends on a specific codec.
   *
   * @return Model codec currently in use by this adapter.
   */
  @Nonnull ModelCodec<Model, Intermediate> codec();

  // -- API: Key Generation -- //
  /**
   * Generate a semi-random opaque token, usable as an ID for a newly-created entity via the model layer. In this case,
   * the ID is returned directly, so it may be used to populate a key.
   *
   * @param instance Model instance to generate an ID for.
   * @return Generated opaque string ID.
   */
  default @Nonnull String generateId(@Nonnull Message instance) {
    return UUID.randomUUID().toString();
  }

  /**
   * Generate a key for a new entity, which must be stored by this driver, but does not yet have a key. If the driver
   * does not support key generation, {@link UnsupportedOperationException} is thrown.
   *
   * <p>Generated keys are expected to be best-effort unique. Generally, Java's built-in {@link java.util.UUID} should
   * do the trick just fine. In more complex or scalable circumstances, this method can be overridden to reach out to
   * the data engine to generate a key.</p>
   *
   * @param instance Default instance of the model type for which a key is desired.
   * @return Generated key for an entity to be stored.
   */
  default @Nonnull Key generateKey(@Nonnull Message instance) {
    // enforce role, key field presence
    var descriptor = instance.getDescriptorForType();
    enforceRole(descriptor, DatapointType.OBJECT);
    var keyType = keyField(descriptor);
    if (!keyType.isPresent()) throw new MissingAnnotatedField(descriptor, FieldType.KEY);

    // convert to builder, grab field builder for key (keys must be top-level fields)
    var builder = instance.newBuilderForType();
    var keyBuilder = builder.getFieldBuilder(keyType.get().getField());
    spliceIdBuilder(keyBuilder, Optional.of(generateId(instance)));

    //noinspection unchecked
    Key obj = (Key)keyBuilder.build();
    if (Internals.logging.isDebugEnabled()) {
      Internals.logging.debug(format("Generated key for record: '%s'.", obj.toString()));
    }
    return obj;
  }

  // -- API: Projections & Field Masking -- //
  /**
   * Apply the fields from {@code source} to {@code target}, considering any provided {@link FieldMask}.
   *
   * <p>If the invoking developer chooses to provide {@code markedPaths}, they must also supply {@code markEffect}. For
   * each field encountered that matches a property path in {@code markedPaths}, {@code markEffect} is applied. This
   * happens recursively for the entire model tree of {@code source} (and, consequently, {@code target}).</p>
   *
   * <p>After all field computations are complete, the builder is built (and casted, if necessary), before being handed
   * back to invoking code.</p>
   *
   * @see FetchOptions.MaskMode Determines how "marked" fields are treated.
   * @param target Builder to set each field value on, as appropriate.
   * @param source Source instance to pull fields and field values from.
   * @param markedPaths "Marked" paths - each one will be treated, as encountered, according to {@code markEffect}.
   * @param markEffect Determines how to treat "marked" paths. See {@link FetchOptions.MaskMode} for more information.
   * @param stackPrefix Dotted stack of properties describing the path that got us to this point (via recursion).
   * @return Constructed model, after applying the provided field mask, as applicable.
   */
  default Message.Builder applyFieldsRecursive(@Nonnull Message.Builder target,
                                               @Nonnull Message source,
                                               @Nonnull Set<String> markedPaths,
                                               @Nonnull FetchOptions.MaskMode markEffect,
                                               @Nonnull String stackPrefix) {
    // otherwise, we must examine each field with a value on the `source`, checking against `markedPaths` (if present)
    // as we go. if it matches, we filter through `markEffect` before applying against `target`.
    for (Map.Entry<FieldDescriptor, Object> property : source.getAllFields().entrySet()) {
      FieldDescriptor field = property.getKey();
      boolean skip = false;
      Object value = property.getValue();
      FetchOptions.MaskMode effect = FetchOptions.MaskMode.INCLUDE.equals(markEffect) ?
        FetchOptions.MaskMode.EXCLUDE : FetchOptions.MaskMode.INCLUDE;

      String currentPath = stackPrefix.isEmpty() ? field.getName() : stackPrefix + "." + field.getName();

      boolean marked = markedPaths.contains(currentPath);
      if (!FieldDescriptor.Type.MESSAGE.equals(field.getType()) && marked) {
        // field is in the marked paths.
        effect = markEffect;
      } else if (FieldDescriptor.Type.MESSAGE.equals(field.getType())) {
        effect = FetchOptions.MaskMode.INCLUDE;  // always include messages
      }

      switch (effect) {
        case PROJECTION:
        case INCLUDE:
          if (Internals.logging.isDebugEnabled()) {
            Internals.logging.debug(format(
              "Field '%s' (%s) included because it did not violate expectation %s via field mask.",
              currentPath,
              field.getFullName(),
              markEffect.name()));
          }

          // handle recursive cases first
          if (FieldDescriptor.Type.MESSAGE.equals(field.getType())) {
            target.setField(
              field,
              applyFieldsRecursive(
                target.getFieldBuilder(field),
                (Message)value,
                markedPaths,
                markEffect,
                currentPath).build());

          } else {
            // it's a simple field value
            target.setField(field, value);
          }
          break;

        case EXCLUDE:
          if (Internals.logging.isDebugEnabled()) {
            Internals.logging.debug(format(
              "Excluded field '%s' (%s) because it did not meet expectation %s via field mask.",
              currentPath,
              field.getFullName(),
              markEffect.name()));
          }
      }
    }
    return target;
  }

  /**
   * Apply mask-related options to the provided instance. This may include re-building <i>without</i> certain fields, so
   * the instance returned may be different.
   *
   * @param instance Instance to filter based on any provided field mask.k
   * @param options Options to apply to the provided instance.
   * @return Model, post-filtering.
   */
  @VisibleForTesting
  default Model applyMask(@Nonnull Model instance, @Nonnull FetchOptions options) {
    // do we have a mask to apply? does it have fields?
    if (instance.isInitialized()
        && options.fieldMask().isPresent()
        && options.fieldMask().get().getPathsCount() > 0) {
      if (Internals.logging.isTraceEnabled())
        Internals.logging.trace(format("Found valid field mask, applying: '%s'.", options.fieldMask().get()));

      // resolve mask & mode
      FieldMask mask = options.fieldMask().get();
      FetchOptions.MaskMode maskMode = Objects.requireNonNull(options.fieldMaskMode(),
        "Cannot provide `null` for field mask mode.");

      //noinspection unchecked
      return (Model)applyFieldsRecursive(
        instance.newBuilderForType(),
        instance,
        ImmutableSet.copyOf(Objects.requireNonNull(mask.getPathsList())),
        maskMode,
        "" /* root path */).build();
    }
    if (Internals.logging.isTraceEnabled())
      Internals.logging.trace("No field mask found. Skipping mask application.");
    return instance;
  }

  // -- API: Fetch -- //
  /**
   * Synchronously retrieve a data model instance from underlying storage, addressed by its unique ID.
   *
   * <p>If the record cannot be located by the storage engine, {@code null} will be returned instead. For a safe variant
   * of this method (relying on {@link Optional}), see {@link #fetchSafe(Message)}.</p>
   *
   * <p><b>Note:</b> Asynchronous and reactive versions of this method also exist. You should always consider using
   * those if your requirements allow.</p>
   *
   * @see #fetchAsync(Message) For an async version of this method, which produces a {@link ListenableFuture}.
   * @see #fetchSafe(Message) For a safe version of this method, which uses {@link Optional} instead of null.
   * @see #fetchReactive(Message) For a reactive version of this method, which produces a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and return it if found.
   * @return Requested record, as a model instance, or {@code null} if one could not be found.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested instance.
   */
  default @Nullable Model fetch(@Nonnull Key key) throws PersistenceException {
    return fetch(key, FetchOptions.DEFAULTS);
  }

  /**
   * Synchronously retrieve a data model instance from underlying storage, addressed by its unique ID.
   *
   * <p>If the record cannot be located by the storage engine, {@code null} will be returned instead. For a safe
   * variant of this method (relying on {@link Optional}), see {@link #fetchSafe(Message)}}. This variant
   * additionally allows specification of {@link FetchOptions}.</p>
   *
   * <p><b>Note:</b> Asynchronous and reactive versions of this method also exist. You should always consider using
   * those if your requirements allow.</p>
   *
   * @see #fetchAsync(Message) For an async version of this method, which produces a {@link ListenableFuture}.
   * @see #fetchSafe(Message) For a safe version of this method, which uses {@link Optional} instead of null.
   * @see #fetchReactive(Message) For a reactive version of this method, which produces a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and return it if found.
   * @param options Options to apply to this individual retrieval operation.
   * @return Requested record, as a model instance, or {@code null} if one could not be found.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested instance.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  default @Nullable Model fetch(@Nonnull Key key, @Nullable FetchOptions options) throws PersistenceException {
    Optional<Model> msg = fetchSafe(key, options);
    return msg.isPresent() ? msg.get() : null;
  }

  /**
   * Safely (and synchronously) retrieve a data model instance from storage, returning {@link Optional#empty()} if it
   * cannot be located, rather than {@code null}.
   *
   * <p><b>Note:</b> Asynchronous and reactive versions of this method also exist. You should always consider using
   * those if your requirements allow. All of the reactive/async methods support null safety with {@link Optional}.</p>
   *
   * @see #fetch(Message) For a simpler, but {@code null}-unsafe version of this method.
   * @see #fetchAsync(Message) For an async version of this metho, which produces a {@link ListenableFuture}.
   * @see #fetchReactive(Message) For a reactive version of this method, which produces a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and return it if found.
   * @return Requested record, as a model instance, or {@link Optional#empty()} if it cannot be found.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  default @Nonnull Optional<Model> fetchSafe(@Nonnull Key key) throws PersistenceException {
    return fetchSafe(key, FetchOptions.DEFAULTS);
  }

  /**
   * Safely (and synchronously) retrieve a data model instance from storage, returning {@link Optional#empty()} if it
   * cannot be located, rather than {@code null}.
   *
   * <p>This variant additionally allows specification of {@link FetchOptions}.</p>
   *
   * <p><b>Note:</b> Asynchronous and reactive versions of this method also exist. You should always consider using
   * those if your requirements allow. All of the reactive/async methods support null safety with {@link Optional}.</p>
   *
   * @see #fetch(Message) For a simpler, but {@code null}-unsafe version of this method.
   * @see #fetchAsync(Message) For an async version of this metho, which produces a {@link ListenableFuture}.
   * @see #fetchReactive(Message) For a reactive version of this method, which produces a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and return it if found.
   * @param options Options to apply to this individual retrieval operation.
   * @return Requested record, as a model instance, or {@link Optional#empty()} if it cannot be found.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  @Nonnull
  default Optional<Model> fetchSafe(@Nonnull Key key, @Nullable FetchOptions options) throws PersistenceException {
    if (Internals.logging.isTraceEnabled())
      Internals.logging.trace(format("Synchronously fetching model with key '%s'. Options follow.\n%s",
        key, options));
    return Internals.convertAsyncExceptions(() -> {
      FetchOptions resolvedOptions = options != null ? options : FetchOptions.DEFAULTS;
      return this.fetchAsync(key, options).get(
        resolvedOptions.timeoutValue().orElse(DEFAULT_TIMEOUT),
        resolvedOptions.timeoutUnit().orElse(DEFAULT_TIMEOUT_UNIT));
    });
  }

  /**
   * Reactively retrieve a data model instance from storage, emitting it over a {@link Publisher} wrapped in an
   * {@link Optional}.
   *
   * <p>In other words, if the model cannot be located, exactly one {@link Optional#empty()} will be emitted over the
   * channel. If the model is successfully located and retrieved, it is emitted exactly once. See other method variants,
   * which allow specification of additional options.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Publisher} channel instead, to enable reactive exception handling.</p>
   *
   * @see #fetch(Message) For a simple, synchronous ({@code null}-unsafe) version of this method.
   * @see #fetchAsync(Message) For an async version of this method, which produces a {@link ListenableFuture}.
   * @see #fetchReactive(Message, FetchOptions) For a variant of this method that allows specification of options.
   * @param key Key at which we should look for the requested entity, and emit it if found.
   * @return Publisher which will receive exactly-one emitted {@link Optional#empty()}, or wrapped object.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  default @Nonnull ReactiveFuture<Optional<Model>> fetchReactive(@Nonnull Key key) {
    return fetchReactive(key, FetchOptions.DEFAULTS);
  }

  /**
   * Reactively retrieve a data model instance from storage, emitting it over a {@link Publisher} wrapped in an
   * {@link Optional}.
   *
   * <p>In other words, if the model cannot be located, exactly one {@link Optional#empty()} will be emitted over the
   * channel. If the model is successfully located and retrieved, it is emitted exactly once. See other method variants,
   * which allow specification of additional options. This method variant additionally allows the specification of
   * {@link FetchOptions}.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Publisher} channel instead, to enable reactive exception handling.</p>
   *
   * @see #fetch(Message) For a simple, synchronous ({@code null}-unsafe) version of this method.
   * @see #fetchAsync(Message) For an async version of this method, which produces a {@link ListenableFuture}.
   * @param key Key at which we should look for the requested entity, and emit it if found.
   * @param options Options to apply to this individual retrieval operation.
   * @return Publisher which will receive exactly-one emitted {@link Optional#empty()}, or wrapped object.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  default @Nonnull ReactiveFuture<Optional<Model>> fetchReactive(@Nonnull Key key, @Nullable FetchOptions options) {
    return this.fetchAsync(key, options);
  }

  /**
   * Asynchronously retrieve a data model instance from storage, which will populate the provided {@link Future} value.
   *
   * <p>All futures emitted via the persistence framework (and Gust writ-large) are {@link ListenableFuture}-compliant
   * implementations under the hood. If the requested record cannot be located, {@link Optional#empty()} is returned as
   * the future value, otherwise, the model is returned. See other method variants, which allow specification of
   * additional options.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Future} channel instead, or raise the exception in the event
   * {@link Future#get()} is called to surface it in the invoking (or dependent) code.</p>
   *
   * @see #fetch(Message) For a simple, synchronous ({@code null}=unsafe) version of this method.
   * @see #fetchSafe(Message) For a simple, synchronous ({@code null}-safe) version of this method.
   * @see #fetchReactive(Message) For a reactive version of this method, which returns a {@link Publisher}.
   * @see #fetchAsync(Message, FetchOptions) For a variant of this method which supports {@link FetchOptions}.
   * @param key Key at which we should look for the requested entity, and emit it if found.
   * @return Future value, which resolves to the specified datamodel instance, or {@link Optional#empty()} if the record
   *         could not be located by the storage engine.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  default @Nonnull ReactiveFuture<Optional<Model>> fetchAsync(@Nonnull Key key) {
    return fetchAsync(key, FetchOptions.DEFAULTS);
  }

  /**
   * Asynchronously retrieve a data model instance from storage, which will populate the provided {@link Future} value.
   *
   * <p>All futures emitted via the persistence framework (and Gust writ-large) are {@link ListenableFuture}-compliant
   * implementations under the hood. If the requested record cannot be located, {@link Optional#empty()} is returned as
   * the future value, otherwise, the model is returned.</p>
   *
   * <p>This method additionally enables specification of custom {@link FetchOptions}, which are applied on a per-
   * operation basis to override global defaults.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Future} channel instead, or raise the exception in the event
   * {@link Future#get()} is called to surface it in the invoking (or dependent) code.</p>
   *
   * @see #fetch(Message) For a simple, synchronous ({@code null}=unsafe) version of this method.
   * @see #fetchSafe(Message) For a simple, synchronous ({@code null}-safe) version of this method.
   * @see #fetchReactive(Message) For a reactive version of this method, which returns a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and emit it if found.
   * @param options Options to apply to this individual retrieval operation.
   * @return Future value, which resolves to the specified datamodel instance, or {@link Optional#empty()} if the record
   *         could not be located by the storage engine.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  @OverridingMethodsMustInvokeSuper
  default @Nonnull ReactiveFuture<Optional<Model>> fetchAsync(@Nonnull Key key, @Nullable FetchOptions options) {
    if (Internals.logging.isTraceEnabled())
      Internals.logging.trace(format("Fetching model with key '%s' asynchronously. Options follow.\n%s",
        key, options));
    return this.retrieve(key, options != null ? options : FetchOptions.DEFAULTS);
  }

  /**
   * Low-level record retrieval method. Effectively called by all other fetch variants. Asynchronously retrieve a data
   * model instance from storage, which will populate the provided {@link ReactiveFuture} value.
   *
   * <p>All futures emitted via the persistence framework (and Gust writ-large) are {@link ListenableFuture}-compliant
   * implementations under the hood. If the requested record cannot be located, {@link Optional#empty()} is returned as
   * the future value, otherwise, the model is returned.</p>
   *
   * <p>This method additionally enables specification of custom {@link FetchOptions}, which are applied on a per-
   * operation basis to override global defaults.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Future} channel instead, or raise the exception in the event
   * {@link Future#get()} is called to surface it in the invoking (or dependent) code.</p>
   *
   * @see #fetch(Message) For a simple, synchronous ({@code null}=unsafe) version of this method.
   * @see #fetchSafe(Message) For a simple, synchronous ({@code null}-safe) version of this method.
   * @see #fetchAsync(Message) For an async variant of this method (identical, except options are optional).
   * @see #fetchReactive(Message) For a reactive version of this method, which returns a {@link Publisher}.
   * @param key Key at which we should look for the requested entity, and emit it if found.
   * @param options Options to apply to this individual retrieval operation.
   * @return Future value, which resolves to the specified datamodel instance, or {@link Optional#empty()} if the record
   *         could not be located by the storage engine.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  @Nonnull ReactiveFuture<Optional<Model>> retrieve(@Nonnull Key key, @Nonnull FetchOptions options);

  // -- API: Create -- //
  /**
   * Low-level record persistence method. Effectively called by all other create/put variants. Asynchronously write a
   * data model instance to storage, which will populate the provided {@link ReactiveFuture} value.
   *
   * <p>Optionally, a key may be provided as a nominated value to the storage engine. Whether the engine accepts
   * nominated keys is up to the implementation. In all cases, the engine must return the key used to store and address
   * the value henceforth.</p>
   *
   * <p>All futures emitted via the persistence framework (and Gust writ-large) are {@link ListenableFuture}-compliant
   * implementations under the hood, but {@link ReactiveFuture} allows a model-layer result to be used as a
   * {@link Future}, or a one-item reactive {@link Publisher}.</p>
   *
   * <p>This method additionally enables specification of custom {@link WriteOptions}, which are applied on a per-
   * operation basis to override global defaults.</p>
   *
   * <p><b>Exceptions:</b> Instead of throwing a {@link PersistenceException} as other methods do, this operation will
   * <i>emit</i> the exception over the {@link Future} channel instead, or raise the exception in the event
   * {@link Future#get()} is called to surface it in the invoking (or dependent) code.</p>
   *
   * @param key Key nominated by invoking code for storing this record. If no key is provided, the underlying storage
   *            engine is expected to allocate one. Where unsupported, {@link PersistenceException} will be thrown.
   * @param model Model to store at the specified key, if provided.
   * @param options Options to apply to this persist operation.
   * @return Reactive future, which resolves to the key where the provided model is now stored. In no case should this
   *         method return {@code null}. Instead, {@link PersistenceException} will be thrown.
   * @throws InvalidModelType If the specified key type is not compatible with model-layer operations.
   * @throws PersistenceException If an unexpected failure occurs, of any kind, while fetching the requested resource.
   * @throws MissingAnnotatedField If the specified key record has no resolvable ID field.
   */
  @Nonnull ReactiveFuture<Model> persist(@Nullable Key key, @Nonnull Model model, @Nonnull WriteOptions options);
}