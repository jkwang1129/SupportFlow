"""Provider-isolated circuit breaker with deterministic clock support."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from contextlib import contextmanager
from enum import Enum
import threading
import time
from typing import Any, TypeVar

T = TypeVar("T")


class CircuitState(str, Enum):
    """Circuit breaker lifecycle states."""

    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class CircuitBreakerOpenError(RuntimeError):
    """Raised immediately when a provider circuit is OPEN or probe slots are full."""

    def __init__(
        self,
        provider: str,
        *,
        state: str = CircuitState.OPEN.value,
        retry_after_seconds: float = 0.0,
        failure_count: int = 0,
    ) -> None:
        self.provider = provider
        self.state = state
        self.retry_after_seconds = round(max(0.0, float(retry_after_seconds)), 3)
        self.failure_count = int(failure_count)
        self.code = "MODEL_CIRCUIT_OPEN"
        self.retryable = True
        self.status_code = 503
        self.attempted = False
        super().__init__(
            f"Circuit breaker for provider '{provider}' is {state} "
            f"(retry_after={self.retry_after_seconds}s)"
        )


def is_transient_failure(exc: BaseException) -> bool:
    """Classify whether an exception represents a transient upstream fault."""
    if isinstance(exc, CircuitBreakerOpenError):
        return False
    retryable = getattr(exc, "retryable", None)
    if isinstance(retryable, bool):
        return retryable
    status_code = getattr(exc, "status_code", None)
    if isinstance(status_code, int):
        return status_code in (408, 425, 429) or status_code >= 500
    code = getattr(exc, "code", None)
    if isinstance(code, str) and code in {
        "MODEL_RATE_LIMITED",
        "MODEL_UNAVAILABLE",
        "MODEL_TIMEOUT",
        "MODEL_NETWORK_ERROR",
    }:
        return True
    return isinstance(exc, (TimeoutError, ConnectionError, OSError))


class CircuitBreaker:
    """Thread-safe provider circuit breaker supporting CLOSED / OPEN / HALF_OPEN."""

    def __init__(
        self,
        name: str = "default",
        *,
        failure_threshold: int = 5,
        recovery_timeout_seconds: float = 30.0,
        half_open_max_calls: int = 1,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if not isinstance(name, str) or not name.strip():
            raise ValueError("name must be a non-empty string")
        if int(failure_threshold) < 1:
            raise ValueError("failure_threshold must be >= 1")
        if float(recovery_timeout_seconds) <= 0:
            raise ValueError("recovery_timeout_seconds must be > 0")
        if int(half_open_max_calls) < 1:
            raise ValueError("half_open_max_calls must be >= 1")
        if not callable(clock):
            raise ValueError("clock must be callable")

        self.name = name.strip()
        self.failure_threshold = int(failure_threshold)
        self.recovery_timeout_seconds = float(recovery_timeout_seconds)
        self.half_open_max_calls = int(half_open_max_calls)
        self._clock = clock
        self._lock = threading.RLock()

        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._opened_at: float | None = None
        self._half_open_in_flight = 0
        self._rejected_calls = 0
        self._total_trips = 0
        self._total_calls = 0
        self._total_failures = 0
        self._total_successes = 0
        self._last_failure_code: str | None = None

    def _refresh_state_locked(self, now: float) -> CircuitState:
        if (
            self._state is CircuitState.OPEN
            and self._opened_at is not None
            and (now - self._opened_at) >= self.recovery_timeout_seconds
        ):
            self._state = CircuitState.HALF_OPEN
            self._half_open_in_flight = 0
        return self._state

    @property
    def state(self) -> CircuitState:
        with self._lock:
            return self._refresh_state_locked(self._clock())

    def before_call(self) -> CircuitState:
        """Acquire permission to execute a call or raise CircuitBreakerOpenError."""
        with self._lock:
            now = self._clock()
            state = self._refresh_state_locked(now)
            if state is CircuitState.OPEN:
                self._rejected_calls += 1
                elapsed = (now - self._opened_at) if self._opened_at is not None else 0.0
                retry_after = max(0.0, self.recovery_timeout_seconds - elapsed)
                raise CircuitBreakerOpenError(
                    self.name,
                    state=state.value,
                    retry_after_seconds=retry_after,
                    failure_count=self._failure_count,
                )
            if state is CircuitState.HALF_OPEN:
                if self._half_open_in_flight >= self.half_open_max_calls:
                    self._rejected_calls += 1
                    raise CircuitBreakerOpenError(
                        self.name,
                        state=state.value,
                        retry_after_seconds=self.recovery_timeout_seconds,
                        failure_count=self._failure_count,
                    )
                self._half_open_in_flight += 1
                self._total_calls += 1
                return CircuitState.HALF_OPEN

            self._total_calls += 1
            return CircuitState.CLOSED

    def record_success(self) -> None:
        """Record a successful upstream call and close circuit if probing."""
        with self._lock:
            self._total_successes += 1
            if self._state is CircuitState.HALF_OPEN:
                self._half_open_in_flight = max(0, self._half_open_in_flight - 1)
                self._state = CircuitState.CLOSED
                self._failure_count = 0
                self._opened_at = None
                self._half_open_in_flight = 0
                self._last_failure_code = None
            elif self._state is CircuitState.CLOSED:
                self._failure_count = 0
                self._last_failure_code = None

    def record_failure(
        self,
        *,
        transient: bool = True,
        reason: str | None = None,
    ) -> None:
        """Record an upstream call failure; trips on threshold or HALF_OPEN probe fault."""
        with self._lock:
            if not transient:
                if self._state is CircuitState.HALF_OPEN:
                    self._half_open_in_flight = max(0, self._half_open_in_flight - 1)
                return

            now = self._clock()
            self._total_failures += 1
            if reason:
                self._last_failure_code = str(reason)[:64]

            if self._state is CircuitState.HALF_OPEN:
                self._half_open_in_flight = max(0, self._half_open_in_flight - 1)
                self._failure_count += 1
                self._trip_locked(now)
                return

            self._failure_count += 1
            if self._state is CircuitState.CLOSED and self._failure_count >= self.failure_threshold:
                self._trip_locked(now)

    def _trip_locked(self, now: float) -> None:
        self._state = CircuitState.OPEN
        self._opened_at = now
        self._half_open_in_flight = 0
        self._total_trips += 1

    @contextmanager
    def protect(self):
        """Context manager guarding a synchronous block with circuit state transitions."""
        self.before_call()
        try:
            yield self
        except BaseException as exc:
            self.record_failure(
                transient=is_transient_failure(exc),
                reason=getattr(exc, "code", type(exc).__name__),
            )
            raise
        else:
            self.record_success()

    def call(self, fn: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        """Execute a synchronous callable under circuit breaker protection."""
        with self.protect():
            return fn(*args, **kwargs)

    async def call_async(
        self,
        fn: Callable[..., Awaitable[T]],
        *args: Any,
        **kwargs: Any,
    ) -> T:
        """Execute an async callable under circuit breaker protection."""
        self.before_call()
        try:
            result = await fn(*args, **kwargs)
        except BaseException as exc:
            self.record_failure(
                transient=is_transient_failure(exc),
                reason=getattr(exc, "code", type(exc).__name__),
            )
            raise
        self.record_success()
        return result

    def reset(self) -> None:
        """Reset breaker state and counters back to initial CLOSED state."""
        with self._lock:
            self._state = CircuitState.CLOSED
            self._failure_count = 0
            self._opened_at = None
            self._half_open_in_flight = 0
            self._rejected_calls = 0
            self._total_trips = 0
            self._total_calls = 0
            self._total_failures = 0
            self._total_successes = 0
            self._last_failure_code = None

    def snapshot(self) -> dict[str, Any]:
        """Return immutable telemetry snapshot for LLMOps observability."""
        with self._lock:
            state = self._refresh_state_locked(self._clock())
            return {
                "name": self.name,
                "state": state.value,
                "failure_count": self._failure_count,
                "failure_threshold": self.failure_threshold,
                "recovery_timeout_seconds": self.recovery_timeout_seconds,
                "half_open_max_calls": self.half_open_max_calls,
                "opened_at": self._opened_at,
                "half_open_in_flight": self._half_open_in_flight,
                "rejected_calls": self._rejected_calls,
                "total_trips": self._total_trips,
                "total_calls": self._total_calls,
                "total_failures": self._total_failures,
                "total_successes": self._total_successes,
                "last_failure_code": self._last_failure_code,
            }


class CircuitBreakerRegistry:
    """Provider-isolated registry ensuring one provider's outage never blocks peers."""

    def __init__(
        self,
        *,
        failure_threshold: int = 5,
        recovery_timeout_seconds: float = 30.0,
        half_open_max_calls: int = 1,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.failure_threshold = int(failure_threshold)
        self.recovery_timeout_seconds = float(recovery_timeout_seconds)
        self.half_open_max_calls = int(half_open_max_calls)
        self._clock = clock
        self._lock = threading.RLock()
        self._breakers: dict[str, CircuitBreaker] = {}

    def get(self, provider: str) -> CircuitBreaker:
        key = (provider or "default").strip().lower() or "default"
        with self._lock:
            breaker = self._breakers.get(key)
            if breaker is None:
                breaker = CircuitBreaker(
                    key,
                    failure_threshold=self.failure_threshold,
                    recovery_timeout_seconds=self.recovery_timeout_seconds,
                    half_open_max_calls=self.half_open_max_calls,
                    clock=self._clock,
                )
                self._breakers[key] = breaker
            return breaker

    def snapshots(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            items = list(self._breakers.items())
        return {name: breaker.snapshot() for name, breaker in items}

    def reset(self, provider: str | None = None) -> None:
        with self._lock:
            if provider is None:
                for breaker in self._breakers.values():
                    breaker.reset()
                self._breakers.clear()
                return
            key = provider.strip().lower()
            if key in self._breakers:
                self._breakers[key].reset()


default_circuit_breaker_registry = CircuitBreakerRegistry()


def get_circuit_breaker(provider: str) -> CircuitBreaker:
    """Return the shared provider-isolated CircuitBreaker instance."""
    return default_circuit_breaker_registry.get(provider)
