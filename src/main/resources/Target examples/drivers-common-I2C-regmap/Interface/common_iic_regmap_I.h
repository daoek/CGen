/***
 * @file common_iic_regmap_I.h
 * @brief This file contains the portable common I2C register-map interface.
 */

#ifndef COMMON_IIC_REGMAP_I_H_
#define COMMON_IIC_REGMAP_I_H_

#include "common_iic_I.h"

#include <stdint.h>

/**
 * @brief Result of a RegMap operation.
 *
 * Values matching common_iic_status_t are propagated from the I2C interface.
 */
typedef enum
{
	COMMON_IIC_REGMAP_SUCCESS = COMMON_IIC_SUCCESS,
	COMMON_IIC_REGMAP_ERROR = COMMON_IIC_ERROR,
	COMMON_IIC_REGMAP_BUSY = COMMON_IIC_BUSY,
	COMMON_IIC_REGMAP_TIMEOUT = COMMON_IIC_TIMEOUT,
	COMMON_IIC_REGMAP_INVALID_PARAM = COMMON_IIC_INVALID_PARAM,
	COMMON_IIC_REGMAP_NOT_INITIALIZED = COMMON_IIC_NOT_INITIALIZED,
	COMMON_IIC_REGMAP_NOT_SUPPORTED = COMMON_IIC_NOT_SUPPORTED,
	COMMON_IIC_REGMAP_UNKNOWN_ERROR = COMMON_IIC_UNKNOWN_ERROR,
	COMMON_IIC_REGMAP_INVALID_REGISTER = 8,
	COMMON_IIC_REGMAP_ACCESS_DENIED = 9
} common_iic_regmap_status_t;

/**
 * @brief Portable RegMap operation table.
 *
 * The implementation owns context. The caller supplies the I2C controller and
 * slave address for every transaction.
 */
typedef struct
{
	void *context;
	common_iic_regmap_status_t (*read)(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t *value);
	common_iic_regmap_status_t (*write)(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t value);
	common_iic_regmap_status_t (*update_bits)(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t mask, uint32_t value);
} common_iic_regmap_interface_t;

/**
 * @brief Reads one register.
 *
 * @param interface RegMap interface created for the register layout.
 * @param iic_interface Started common I2C interface.
 * @param slave_address Native I2C slave address.
 * @param register_id Register-table index.
 * @param value Destination for the masked value.
 * @return A RegMap or propagated common I2C status.
 */
static inline common_iic_regmap_status_t
common_iic_regmap_read(common_iic_regmap_interface_t *interface, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t *value)
{
	if (interface == NULL)
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if ((interface->context == NULL) || (interface->read == NULL))
	{
		return COMMON_IIC_REGMAP_NOT_INITIALIZED;
	}

	return interface->read(interface->context, iic_interface, slave_address, register_id, value);
}

/**
 * @brief Writes one register.
 *
 * @param interface RegMap interface created for the register layout.
 * @param iic_interface Started common I2C interface.
 * @param slave_address Native I2C slave address.
 * @param register_id Register-table index.
 * @param value Value that fits the data width and register mask.
 * @return A RegMap or propagated common I2C status.
 */
static inline common_iic_regmap_status_t
common_iic_regmap_write(common_iic_regmap_interface_t *interface, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t value)
{
	if (interface == NULL)
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if ((interface->context == NULL) || (interface->write == NULL))
	{
		return COMMON_IIC_REGMAP_NOT_INITIALIZED;
	}

	return interface->write(interface->context, iic_interface, slave_address, register_id, value);
}

/**
 * @brief Performs a read-modify-write on selected valid bits.
 *
 * The effective mask is mask & register.mask.
 *
 * @param interface RegMap interface created for the register layout.
 * @param iic_interface Started common I2C interface.
 * @param slave_address Native I2C slave address.
 * @param register_id Register-table index.
 * @param mask Bits to update.
 * @param value Replacement bits.
 * @return A RegMap or propagated common I2C status.
 */
static inline common_iic_regmap_status_t
common_iic_regmap_update_bits(common_iic_regmap_interface_t *interface, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t mask, uint32_t value)
{
	if (interface == NULL)
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if ((interface->context == NULL) || (interface->update_bits == NULL))
	{
		return COMMON_IIC_REGMAP_NOT_INITIALIZED;
	}

	return interface->update_bits(interface->context, iic_interface, slave_address, register_id, mask, value);
}

#endif /* COMMON_IIC_REGMAP_I_H_ */
