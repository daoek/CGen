
#include "ra_iic_master.h"

#ifndef RA_IIC_TRANSFER_TIMEOUT_MS
#define RA_IIC_TRANSFER_TIMEOUT_MS (100U)
#endif

#ifndef RA_IIC_MUTEX_TIMEOUT_MS
#define RA_IIC_MUTEX_TIMEOUT_MS (100U)
#endif

#define RA_IIC_INVALID_SLAVE_ADDRESS (UINT32_MAX)

static common_iic_status_t ra_iic_master_lock(ra_iic_master_context_t *context);
static common_iic_status_t ra_iic_master_unlock(ra_iic_master_context_t *context, common_iic_status_t transfer_status);
static bool ra_iic_master_is_valid_slave_address(i2c_master_addr_mode_t addr_mode, uint32_t slave_address);
static common_iic_status_t ra_iic_master_start(void *context);
static common_iic_status_t ra_iic_master_stop(void *context);
static common_iic_status_t ra_iic_master_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t data_length);
static common_iic_status_t ra_iic_master_read(void *context, uint32_t slave_address, uint8_t *data, uint32_t data_length);
static common_iic_status_t ra_iic_master_write_read(void *context, uint32_t slave_address, const uint8_t *tx_data, uint32_t tx_data_length, uint8_t *rx_data, uint32_t rx_data_length);
static void ra_iic_master_callback(i2c_master_callback_args_t *p_args);

static common_iic_status_t ra_iic_master_lock(ra_iic_master_context_t *context)
{
    osal_status_t const status = osal_mutex_lock(&context->mutex_lock, RA_IIC_MUTEX_TIMEOUT_MS);

    if (status == OSAL_STATUS_SUCCES)
    {
        return COMMON_IIC_SUCCESS;
    }

    if (status == OSAL_STATUS_TIMEOUT)
    {
        return COMMON_IIC_TIMEOUT;
    }

    return COMMON_IIC_ERROR;
}

static common_iic_status_t ra_iic_master_unlock(ra_iic_master_context_t *context, common_iic_status_t transfer_status)
{
    if (osal_mutex_unlock(&context->mutex_lock) != OSAL_STATUS_SUCCES)
    {
        return COMMON_IIC_ERROR;
    }

    return transfer_status;
}

static bool ra_iic_master_is_valid_slave_address(i2c_master_addr_mode_t addr_mode, uint32_t slave_address)
{
    if (addr_mode == I2C_MASTER_ADDR_MODE_7BIT)
    {
        return slave_address <= 0x7FU;
    }

    if (addr_mode == I2C_MASTER_ADDR_MODE_10BIT)
    {
        return slave_address <= 0x3FFU;
    }

    return false;
}

common_iic_status_t ra_iic_master_create(common_iic_interface_t *interface, ra_iic_master_context_t *context, i2c_master_instance_t *hardware_driver, i2c_master_addr_mode_t addr_mode)
{
    if ((interface == NULL) || (context == NULL) || (hardware_driver == NULL) ||
        (hardware_driver->p_api == NULL) || (hardware_driver->p_ctrl == NULL) || (hardware_driver->p_cfg == NULL) ||
        ((addr_mode != I2C_MASTER_ADDR_MODE_7BIT) && (addr_mode != I2C_MASTER_ADDR_MODE_10BIT)))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    context->hardware_driver = hardware_driver;
    context->is_writing = false;
    context->is_reading = false;
    context->transfer_status = COMMON_IIC_SUCCESS;
    context->addr_mode = addr_mode;
    context->slave_address = RA_IIC_INVALID_SLAVE_ADDRESS;

    if (osal_mutex_create(&context->mutex_lock, "ra_iic_master") != OSAL_STATUS_SUCCES)
    {
        return COMMON_IIC_ERROR;
    }

    interface->context = context;
    interface->start = ra_iic_master_start;
    interface->stop = ra_iic_master_stop;
    interface->write = ra_iic_master_write;
    interface->read = ra_iic_master_read;
    interface->write_read = ra_iic_master_write_read;

    return COMMON_IIC_SUCCESS;
}

static common_iic_status_t ra_iic_master_start(void *context)
{
    if (context == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)context;
    i2c_master_instance_t *instance = ra_context->hardware_driver;

    common_iic_status_t const lock_status = ra_iic_master_lock(ra_context);
    if (lock_status != COMMON_IIC_SUCCESS)
    {
        return lock_status;
    }

    fsp_err_t err = instance->p_api->open(instance->p_ctrl, instance->p_cfg);
    if (err != FSP_SUCCESS)
    {
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    // FSP requires an open instance for callbackSet(), and open() restores
    // the callback from the generated configuration.
    err = instance->p_api->callbackSet(instance->p_ctrl, ra_iic_master_callback, ra_context, NULL);
    if (err != FSP_SUCCESS)
    {
        (void)instance->p_api->close(instance->p_ctrl);
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    ra_context->is_writing = false;
    ra_context->is_reading = false;
    ra_context->transfer_status = COMMON_IIC_SUCCESS;
    ra_context->slave_address = RA_IIC_INVALID_SLAVE_ADDRESS;
    return ra_iic_master_unlock(ra_context, COMMON_IIC_SUCCESS);
}

static common_iic_status_t ra_iic_master_stop(void *context)
{
    if (context == NULL)
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)context;
    i2c_master_instance_t *instance = ra_context->hardware_driver;

    common_iic_status_t const lock_status = ra_iic_master_lock(ra_context);
    if (lock_status != COMMON_IIC_SUCCESS)
    {
        return lock_status;
    }

    fsp_err_t err = instance->p_api->abort(instance->p_ctrl);
    if (err != FSP_SUCCESS)
    {
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    err = instance->p_api->close(instance->p_ctrl);
    if (err == FSP_SUCCESS)
    {
        ra_context->is_writing = false;
        ra_context->is_reading = false;
        ra_context->transfer_status = COMMON_IIC_SUCCESS;
        ra_context->slave_address = RA_IIC_INVALID_SLAVE_ADDRESS;
        return ra_iic_master_unlock(ra_context, COMMON_IIC_SUCCESS);
    }
    return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
}

static common_iic_status_t ra_iic_master_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t data_length)
{
    if ((context == NULL) || (data == NULL) || (data_length == 0U))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)context;
    i2c_master_instance_t *instance = ra_context->hardware_driver;

    if (!ra_iic_master_is_valid_slave_address(ra_context->addr_mode, slave_address))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    common_iic_status_t const lock_status = ra_iic_master_lock(ra_context);
    if (lock_status != COMMON_IIC_SUCCESS)
    {
        return lock_status;
    }

    fsp_err_t err = FSP_SUCCESS;
    if (ra_context->slave_address != slave_address)
    {
        err = instance->p_api->slaveAddressSet(instance->p_ctrl, slave_address, ra_context->addr_mode);
        if (err != FSP_SUCCESS)
        {
            return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
        }

        ra_context->slave_address = slave_address;
    }

    // start sending data
    ra_context->transfer_status = COMMON_IIC_SUCCESS;
    ra_context->is_writing = true;
    err = instance->p_api->write(instance->p_ctrl, (uint8_t *)data, data_length, false);

    if (err != FSP_SUCCESS)
    {
        ra_context->is_writing = false;
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    uint32_t const start_time_ms = osal_time_ms();

    // Unsigned subtraction keeps the elapsed-time calculation correct when
    // the OS tick counter wraps.
    while (ra_context->is_writing)
    {
        if ((uint32_t)(osal_time_ms() - start_time_ms) >= RA_IIC_TRANSFER_TIMEOUT_MS)
        {
            // FSP does not invoke the transfer callback after an abort.
            (void)instance->p_api->abort(instance->p_ctrl);
            ra_context->is_writing = false;
            return ra_iic_master_unlock(ra_context, COMMON_IIC_TIMEOUT);
        }

        osal_delay_ms(1U);
    }

    return ra_iic_master_unlock(ra_context, ra_context->transfer_status);
}

static common_iic_status_t ra_iic_master_read(void *context, uint32_t slave_address, uint8_t *data, uint32_t data_length)
{
    if ((context == NULL) || (data == NULL) || (data_length == 0U))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)context;
    i2c_master_instance_t *instance = ra_context->hardware_driver;

    if (!ra_iic_master_is_valid_slave_address(ra_context->addr_mode, slave_address))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    common_iic_status_t const lock_status = ra_iic_master_lock(ra_context);
    if (lock_status != COMMON_IIC_SUCCESS)
    {
        return lock_status;
    }

    fsp_err_t err = FSP_SUCCESS;
    if (ra_context->slave_address != slave_address)
    {
        err = instance->p_api->slaveAddressSet(instance->p_ctrl, slave_address, ra_context->addr_mode);
        if (err != FSP_SUCCESS)
        {
            return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
        }

        ra_context->slave_address = slave_address;
    }

    ra_context->transfer_status = COMMON_IIC_SUCCESS;
    ra_context->is_reading = true;
    err = instance->p_api->read(instance->p_ctrl, data, data_length, false);
    if (err != FSP_SUCCESS)
    {
        ra_context->is_reading = false;
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    uint32_t const start_time_ms = osal_time_ms();

    while (ra_context->is_reading)
    {
        if ((uint32_t)(osal_time_ms() - start_time_ms) >= RA_IIC_TRANSFER_TIMEOUT_MS)
        {
            // FSP does not invoke the transfer callback after an abort.
            (void)instance->p_api->abort(instance->p_ctrl);
            ra_context->is_reading = false;
            return ra_iic_master_unlock(ra_context, COMMON_IIC_TIMEOUT);
        }

        osal_delay_ms(1U);
    }

    return ra_iic_master_unlock(ra_context, ra_context->transfer_status);
}

static common_iic_status_t ra_iic_master_write_read(void *context,
                                                    uint32_t slave_address,
                                                    const uint8_t *tx_data,
                                                    uint32_t tx_data_length,
                                                    uint8_t *rx_data,
                                                    uint32_t rx_data_length)
{
    if ((context == NULL) || (tx_data == NULL) || (tx_data_length == 0U) ||
        (rx_data == NULL) || (rx_data_length == 0U))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)context;
    i2c_master_instance_t *instance = ra_context->hardware_driver;

    if (!ra_iic_master_is_valid_slave_address(ra_context->addr_mode, slave_address))
    {
        return COMMON_IIC_INVALID_PARAM;
    }

    common_iic_status_t const lock_status = ra_iic_master_lock(ra_context);
    if (lock_status != COMMON_IIC_SUCCESS)
    {
        return lock_status;
    }

    fsp_err_t err = FSP_SUCCESS;
    if (ra_context->slave_address != slave_address)
    {
        err = instance->p_api->slaveAddressSet(instance->p_ctrl, slave_address, ra_context->addr_mode);
        if (err != FSP_SUCCESS)
        {
            return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
        }

        ra_context->slave_address = slave_address;
    }

    // Keep control of the bus after the write so the read starts with a
    // repeated-start condition instead of a stop followed by a new start.
    ra_context->transfer_status = COMMON_IIC_SUCCESS;
    ra_context->is_writing = true;
    err = instance->p_api->write(instance->p_ctrl, (uint8_t *)tx_data, tx_data_length, true);
    if (err != FSP_SUCCESS)
    {
        ra_context->is_writing = false;
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    uint32_t start_time_ms = osal_time_ms();
    while (ra_context->is_writing)
    {
        if ((uint32_t)(osal_time_ms() - start_time_ms) >= RA_IIC_TRANSFER_TIMEOUT_MS)
        {
            (void)instance->p_api->abort(instance->p_ctrl);
            ra_context->is_writing = false;
            return ra_iic_master_unlock(ra_context, COMMON_IIC_TIMEOUT);
        }

        osal_delay_ms(1U);
    }

    if (ra_context->transfer_status != COMMON_IIC_SUCCESS)
    {
        return ra_iic_master_unlock(ra_context, ra_context->transfer_status);
    }

    ra_context->is_reading = true;
    err = instance->p_api->read(instance->p_ctrl, rx_data, rx_data_length, false);
    if (err != FSP_SUCCESS)
    {
        // The write completed with restart enabled, so abort to release the
        // bus if the follow-up read could not be started.
        (void)instance->p_api->abort(instance->p_ctrl);
        ra_context->is_reading = false;
        return ra_iic_master_unlock(ra_context, COMMON_IIC_ERROR);
    }

    start_time_ms = osal_time_ms();
    while (ra_context->is_reading)
    {
        if ((uint32_t)(osal_time_ms() - start_time_ms) >= RA_IIC_TRANSFER_TIMEOUT_MS)
        {
            (void)instance->p_api->abort(instance->p_ctrl);
            ra_context->is_reading = false;
            return ra_iic_master_unlock(ra_context, COMMON_IIC_TIMEOUT);
        }

        osal_delay_ms(1U);
    }

    return ra_iic_master_unlock(ra_context, ra_context->transfer_status);
}

static void ra_iic_master_callback(i2c_master_callback_args_t *p_args)
{
    ra_iic_master_context_t *ra_context = (ra_iic_master_context_t *)p_args->p_context;

    switch (p_args->event)
    {
    case I2C_MASTER_EVENT_TX_COMPLETE:
        ra_context->is_writing = false;
        break;
    case I2C_MASTER_EVENT_RX_COMPLETE:
        ra_context->is_reading = false;
        break;
    case I2C_MASTER_EVENT_ABORTED:
        ra_context->transfer_status = COMMON_IIC_ERROR;
        ra_context->is_writing = false;
        ra_context->is_reading = false;
        break;
    default:
        break;
    }
}
