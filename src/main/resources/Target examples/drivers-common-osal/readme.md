# Operating System Abstraction Layer (OSAL)

`osal.h` is a small, header-only abstraction for the operating-system services
used by the common drivers. It lets a driver use delays, timekeeping, task
yielding, and mutexes without directly depending on a particular RTOS API.

## Supported targets

Azure RTOS ThreadX is the currently implemented target. Define
`AZURE_RTOS_THREADX` for every translation unit that includes `osal.h`; the
header then includes `tx_api.h` and maps the OSAL functions to ThreadX.

```c
#define AZURE_RTOS_THREADX
#include "osal.h"
```

Define the target in the build configuration (for example, as a compiler
definition), rather than in individual source files, so all code uses the same
OSAL target.

Exactly one target selector is required:

| Selector | Status |
| --- | --- |
| `AZURE_RTOS_THREADX` | Supported |
| `OS_FREERTOS` | Declared, but not implemented; compilation stops with an error |
| `OS_BARE_METAL` | Declared, but not implemented |

If no selector, or more than one selector, is defined, `osal.h` emits a
compile-time error.

## API

All functions are `static inline`; no OSAL source file or library needs to be
linked.

| Function | ThreadX behavior |
| --- | --- |
| `osal_delay_ms(uint32_t milliseconds)` | Sleeps the current thread. A non-zero delay that converts to less than one ThreadX tick sleeps for one tick. Zero returns immediately. |
| `osal_yield(void)` | Relinquishes the current thread's remaining time slice. |
| `osal_time_ms(void)` | Returns elapsed ThreadX time converted to milliseconds. The `uint32_t` result wraps naturally. |
| `osal_mutex_create(osal_mutex_t *mutex, char *name)` | Creates a priority-inheritance ThreadX mutex. |
| `osal_mutex_lock(osal_mutex_t *mutex, uint32_t timeout_ms)` | Acquires a mutex, returning immediately when the converted timeout is zero. |
| `osal_mutex_unlock(osal_mutex_t *mutex)` | Releases a mutex. |

`osal_mutex_t` owns the underlying ThreadX `TX_MUTEX` control block. Keep the
`osal_mutex_t` object alive and do not create the same object more than once.

## Status values

Mutex functions return `osal_status_t`:

| Value | Meaning |
| --- | --- |
| `OSAL_STATUS_SUCCES` | The operation succeeded. The spelling is part of the public API. |
| `OSAL_STATUS_TIMEOUT` | A lock attempt could not acquire the mutex within its timeout. |
| `OSAL_STATUS_ERROR` | The underlying OS operation failed for another reason. |
| `OSAL_STATUS_FAIL` | Reserved status value; not currently returned by this implementation. |

## Example

```c
#include "osal.h"

static osal_mutex_t bus_mutex;

void bus_init(void)
{
    if (osal_mutex_create(&bus_mutex, "bus_mutex") != OSAL_STATUS_SUCCES)
    {
        /* Handle initialization failure. */
    }
}

osal_status_t bus_write(void)
{
    osal_status_t status = osal_mutex_lock(&bus_mutex, 100U);
    if (status != OSAL_STATUS_SUCCES)
    {
        return status;
    }

    /* Access the shared bus. */

    return osal_mutex_unlock(&bus_mutex);
}
```

## Timeouts and context

The OSAL converts millisecond values using `TX_TIMER_TICKS_PER_SECOND`.
`osal_delay_ms()` rounds a positive sub-tick delay up to one tick. Mutex lock
timeouts use integer conversion and therefore round down; a positive timeout
shorter than one tick becomes a non-blocking attempt. Choose timeout values
that align with the configured ThreadX tick rate when exact timing matters.

These functions inherit the applicable ThreadX calling-context restrictions.
In particular, use the blocking delay and mutex operations from thread context,
not from interrupt context.
