package io.ecucore.shared

/**
 * Em JVM/Android vira kotlin.jvm.Synchronized (mesmo bytecode de antes do port);
 * em Kotlin/Native é no-op — lá o protocolo roda em fluxo de coroutine único.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
expect annotation class JvmSynchronized()
