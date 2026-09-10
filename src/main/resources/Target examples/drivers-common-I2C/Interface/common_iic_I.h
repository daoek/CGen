/***
 * @file common_iic_I.h
 * @brief This file contains the declarations for the common I2C interface functions and types.
 */

#ifndef COMMON_IIC_I_H_
#define COMMON_IIC_I_H_

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
typedef enum
{
    COMMON_IIC_SUCCESS = 0,
    COMMON_IIC_ERROR = 1,
    COMMON_IIC_BUSY = 2,
    COMMON_IIC_TIMEOUT = 3,
    COMMON_IIC_INVALID_PARAM = 4,
    COMMON_IIC_NOT_INITIALIZED = 5,
    COMMON_IIC_NOT_SUPPORTED = 6,
    COMMON_IIC_UNKNOWN_ERROR = 7
} common_iic_status_t;

typedef struct
{
    void *context;
    common_iic_status_t (*start)(void *context);
    common_iic_status_t (*stop)(void *context);
    common_iic_status_t (*write)(void *context, uint32_t slave_address, const uint8_t *data, uint32_t data_length);
    common_iic_status_t (*read)(void *context, uint32_t slave_address, uint8_t *data, uint32_t data_length);
    common_iic_status_t (*write_read)(void *context, uint32_t slave_address, const uint8_t *tx_data, uint32_t tx_data_length, uint8_t *rx_data, uint32_t rx_data_length);
} common_iic_interface_t;

static inline common_iic_status_t common_iic_start(common_iic_interface_t *interface)
{
    if (interface == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    if ((interface->context == NULL) || (interface->start == NULL))
    {
        return COMMON_IIC_NOT_INITIALIZED;
    }

    return interface->start(interface->context);
}

static inline common_iic_status_t common_iic_stop(common_iic_interface_t *interface)
{
    if (interface == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    if ((interface->context == NULL) || (interface->stop == NULL))
    {
        return COMMON_IIC_NOT_INITIALIZED;
    }

    return interface->stop(interface->context);
}

static inline common_iic_status_t common_iic_write(common_iic_interface_t *interface, uint32_t slave_address, const uint8_t *data, uint32_t data_length)
{
    if (interface == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    if ((interface->context == NULL) || (interface->write == NULL))
    {
        return COMMON_IIC_NOT_INITIALIZED;
    }

    return interface->write(interface->context, slave_address, data, data_length);
}

static inline common_iic_status_t common_iic_read(common_iic_interface_t *interface, uint32_t slave_address, uint8_t *data, uint32_t data_length)
{
    if (interface == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    if ((interface->context == NULL) || (interface->read == NULL))
    {
        return COMMON_IIC_NOT_INITIALIZED;
    }

    return interface->read(interface->context, slave_address, data, data_length);
}

static inline common_iic_status_t common_iic_write_read(common_iic_interface_t *interface, uint32_t slave_address, const uint8_t *tx_data, uint32_t tx_data_length, uint8_t *rx_data, uint32_t rx_data_length)
{
    if (interface == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    if ((interface->context == NULL) || (interface->write_read == NULL))
    {
        return COMMON_IIC_NOT_INITIALIZED;
    }

    return interface->write_read(interface->context, slave_address, tx_data, tx_data_length, rx_data, rx_data_length);
}

#endif /* COMMON_IIC_I_H_ */
