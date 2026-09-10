/***
 * @file osal.h
 * @brief This file contains the declarations for the Operating System Abstraction Layer (OSAL) functions and types.
 * The OSAL provides a uniform interface for interacting with different operating systems, allowing for easier portability of code across platforms.
 */

#ifndef OSAL_H_
#define OSAL_H_

#include <stdint.h>

/* ThreadX is selected by its native marker; other OS targets are explicit. */
#if defined(OS_FREERTOS)
#include "FreeRTOS.h"
#include "task.h"
#error not implemented yet
#elif defined(AZURE_RTOS_THREADX)
#include "tx_api.h"

typedef struct
{
    TX_MUTEX control_block;
} osal_mutex_t;

#elif defined(OS_BARE_METAL)

#else
// When no os defined create empty object so function can be compiled
typedef struct
{
} osal_mutex_t;
#endif

typedef enum
{
    OSAL_STATUS_SUCCES = 0,
    OSAL_STATUS_ERROR = 1,
    OSAL_STATUS_FAIL = 2,
    OSAL_STATUS_TIMEOUT = 3
} osal_status_t;

/* Exactly one target must be active. */
#if (defined(OS_FREERTOS) + defined(AZURE_RTOS_THREADX) + defined(OS_BARE_METAL)) != 1
#error "Select exactly one OSAL target: OS_FREERTOS, Azure RTOS ThreadX, or OS_BARE_METAL."
#endif

/***
 * @brief Delay the current task or thread for a specified number of milliseconds.
 * @param milliseconds The number of milliseconds to delay. If zero, the function returns immediately.
 */
static inline void osal_delay_ms(uint32_t milliseconds)
{
    if (milliseconds == 0U)
    {
        return;
    }

#if defined(OS_FREERTOS)
    vTaskDelay(pdMS_TO_TICKS(milliseconds));
#elif defined(AZURE_RTOS_THREADX)
    ULONG ticks = (milliseconds * TX_TIMER_TICKS_PER_SECOND) / 1000U;
    if (ticks == 0U)
    {
        ticks = 1U;
    }
    tx_thread_sleep(ticks);
#elif defined(OS_BARE_METAL)
#error not implemented yet
#endif
}

/***
 * @brief Yield the current task or thread, allowing other tasks of equal priority to run.
 */
static inline void osal_yield(void)
{
#if defined(OS_FREERTOS)
    taskYIELD();
#elif defined(AZURE_RTOS_THREADX)
    tx_thread_relinquish();
#elif defined(OS_BARE_METAL)
#error not implemented yet
#endif
}

/***
 * @brief Get the current system time in milliseconds since the OS started.
 * @return The current system time in milliseconds.
 */
static inline uint32_t osal_time_ms(void)
{
#if defined(OS_FREERTOS)
    return (uint32_t)pdTICKS_TO_MS(xTaskGetTickCount());
#elif defined(AZURE_RTOS_THREADX)
    return ((uint32_t)tx_time_get() * 1000U) / TX_TIMER_TICKS_PER_SECOND;
#elif defined(OS_BARE_METAL)
#error not implemented yet
#endif
}

static inline osal_status_t osal_mutex_create(osal_mutex_t *mutex, char *name)
{
#if defined(AZURE_RTOS_THREADX)

    UINT status = tx_mutex_create(&mutex->control_block, name, TX_INHERIT);

    if (status == TX_SUCCESS)
    {
        return OSAL_STATUS_SUCCES;
    }

    return OSAL_STATUS_ERROR;

#elif defined(OS_FREERTOS)
#error not implemented yet
#endif
}

/***
 * @brief Acquire a mutex, waiting for at most the specified time.
 * @param mutex Pointer to the mutex to acquire.
 * @param timeout_ms Maximum time to wait in milliseconds. Zero performs a non-blocking attempt.
 * @return OSAL_STATUS_SUCCES on acquisition, OSAL_STATUS_TIMEOUT when unavailable by the deadline,
 *         or OSAL_STATUS_ERROR for another failure.
 */
static inline osal_status_t osal_mutex_lock(osal_mutex_t *mutex, uint32_t timeout_ms)
{
#if defined(AZURE_RTOS_THREADX)

    ULONG const ticks = (timeout_ms * TX_TIMER_TICKS_PER_SECOND) / 1000U;
    UINT status = tx_mutex_get(&mutex->control_block, ticks);

    if (status == TX_SUCCESS)
    {
        return OSAL_STATUS_SUCCES;
    }

    if (status == TX_NOT_AVAILABLE)
    {
        return OSAL_STATUS_TIMEOUT;
    }

    return OSAL_STATUS_ERROR;

#elif defined(OS_FREERTOS)
#error not implemented yet
#endif
}

static inline osal_status_t osal_mutex_unlock(osal_mutex_t *mutex)
{
#if defined(AZURE_RTOS_THREADX)

    UINT status = tx_mutex_put(&mutex->control_block);

    if (status == TX_SUCCESS)
    {
        return OSAL_STATUS_SUCCES;
    }

    return OSAL_STATUS_ERROR;

#elif defined(OS_FREERTOS)
#error not implemented yet
#endif
}

#endif /* OSAL_H_ */
