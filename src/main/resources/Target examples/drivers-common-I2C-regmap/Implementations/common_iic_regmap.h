/***
 * @file common_iic_regmap.h
 * @brief This file contains the common I2C register-map declarations.
 */

#ifndef COMMON_IIC_REGMAP_H_
#define COMMON_IIC_REGMAP_H_

#include "common_iic_regmap_I.h"

/** @brief Register access permission. */
typedef enum
{
	COMMON_IIC_REGMAP_ACCESS_NONE = 0,
	COMMON_IIC_REGMAP_ACCESS_READ_ONLY,
	COMMON_IIC_REGMAP_ACCESS_WRITE_ONLY,
	COMMON_IIC_REGMAP_ACCESS_READ_WRITE
} common_iic_regmap_access_t;

/** @brief Byte order used for register addresses or register data. */
typedef enum
{
	COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN = 0,
	COMMON_IIC_REGMAP_BYTE_ORDER_LITTLE_ENDIAN
} common_iic_regmap_byte_order_t;

/** @brief Metadata for one register-table entry. */
typedef struct
{
	uint32_t address;
	uint32_t mask;
	common_iic_regmap_access_t access;
} common_iic_regmap_register_t;

/**
 * @brief Reusable description of one device register layout.
 *
 * It deliberately has no I2C interface or slave address.
 */
typedef struct
{
	const common_iic_regmap_register_t *registers;
	uint32_t register_count;
	uint8_t register_address_width;
	uint8_t register_data_width;
	common_iic_regmap_byte_order_t register_address_byte_order;
	common_iic_regmap_byte_order_t register_data_byte_order;
} common_iic_regmap_t;

/**
 * @brief Validates a map and binds it to a RegMap interface.
 *
 * @param interface Interface to populate.
 * @param regmap Register layout to use as the interface context.
 * @return COMMON_IIC_REGMAP_SUCCESS or COMMON_IIC_REGMAP_INVALID_PARAM.
 */
common_iic_regmap_status_t common_iic_regmap_create(common_iic_regmap_interface_t *interface, common_iic_regmap_t *regmap);

#endif /* COMMON_IIC_REGMAP_H_ */
