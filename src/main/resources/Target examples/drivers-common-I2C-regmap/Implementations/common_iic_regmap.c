#include "common_iic_regmap.h"

enum
{
	COMMON_IIC_REGMAP_MAX_WIDTH = 4U
};

static void common_iic_regmap_pack(uint32_t value, uint8_t width, common_iic_regmap_byte_order_t byte_order, uint8_t *data);
static uint32_t common_iic_regmap_unpack(const uint8_t *data, uint8_t width, common_iic_regmap_byte_order_t byte_order);
static common_iic_regmap_status_t common_iic_regmap_read_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t *value);
static common_iic_regmap_status_t common_iic_regmap_write_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t value);
static common_iic_regmap_status_t common_iic_regmap_update_bits_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t mask, uint32_t value);

static void common_iic_regmap_pack(uint32_t value, uint8_t width, common_iic_regmap_byte_order_t byte_order, uint8_t *data)
{
	for (uint32_t byte_index = 0U; byte_index < (uint32_t)width; byte_index++)
	{
		uint32_t const shift = (byte_order == COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN) ? (((uint32_t)width - 1U - byte_index) * 8U) : (byte_index * 8U);

		data[byte_index] = (uint8_t)(value >> shift);
	}
}

static uint32_t common_iic_regmap_unpack(const uint8_t *data, uint8_t width, common_iic_regmap_byte_order_t byte_order)
{
	uint32_t value = 0U;

	for (uint32_t byte_index = 0U; byte_index < (uint32_t)width; byte_index++)
	{
		uint32_t const shift = (byte_order == COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN) ? (((uint32_t)width - 1U - byte_index) * 8U) : (byte_index * 8U);

		value |= (uint32_t)data[byte_index] << shift;
	}

	return value;
}

common_iic_regmap_status_t common_iic_regmap_create(common_iic_regmap_interface_t *interface, common_iic_regmap_t *regmap)
{
	if ((interface == NULL) || (regmap == NULL) || (regmap->registers == NULL) || (regmap->register_count == 0U) || (regmap->register_address_width == 0U) ||
	    (regmap->register_address_width > COMMON_IIC_REGMAP_MAX_WIDTH) || (regmap->register_data_width == 0U) || (regmap->register_data_width > COMMON_IIC_REGMAP_MAX_WIDTH) ||
	    ((regmap->register_address_byte_order != COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN) && (regmap->register_address_byte_order != COMMON_IIC_REGMAP_BYTE_ORDER_LITTLE_ENDIAN)) ||
	    ((regmap->register_data_byte_order != COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN) && (regmap->register_data_byte_order != COMMON_IIC_REGMAP_BYTE_ORDER_LITTLE_ENDIAN)))
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	uint32_t const address_width_mask = UINT32_MAX >> ((COMMON_IIC_REGMAP_MAX_WIDTH - (uint32_t)regmap->register_address_width) * 8U);
	uint32_t const data_width_mask = UINT32_MAX >> ((COMMON_IIC_REGMAP_MAX_WIDTH - (uint32_t)regmap->register_data_width) * 8U);

	for (uint32_t register_id = 0U; register_id < regmap->register_count; register_id++)
	{
		const common_iic_regmap_register_t *const register_entry = &regmap->registers[register_id];

		if (((register_entry->address & ~address_width_mask) != 0U) || ((register_entry->mask & ~data_width_mask) != 0U) ||
		    ((register_entry->access != COMMON_IIC_REGMAP_ACCESS_NONE) && (register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_ONLY) &&
		     (register_entry->access != COMMON_IIC_REGMAP_ACCESS_WRITE_ONLY) && (register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_WRITE)))
		{
			return COMMON_IIC_REGMAP_INVALID_PARAM;
		}
	}

	interface->context = regmap;
	interface->read = common_iic_regmap_read_implementation;
	interface->write = common_iic_regmap_write_implementation;
	interface->update_bits = common_iic_regmap_update_bits_implementation;
	return COMMON_IIC_REGMAP_SUCCESS;
}

static common_iic_regmap_status_t common_iic_regmap_read_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t *value)
{
	const common_iic_regmap_t *const regmap = (const common_iic_regmap_t *)context;
	if ((regmap == NULL) || (iic_interface == NULL) || (value == NULL))
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if (register_id >= regmap->register_count)
	{
		return COMMON_IIC_REGMAP_INVALID_REGISTER;
	}

	const common_iic_regmap_register_t *const register_entry = &regmap->registers[register_id];
	if ((register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_ONLY) && (register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_WRITE))
	{
		return COMMON_IIC_REGMAP_ACCESS_DENIED;
	}

	uint8_t address_data[COMMON_IIC_REGMAP_MAX_WIDTH];
	uint8_t value_data[COMMON_IIC_REGMAP_MAX_WIDTH];
	common_iic_regmap_pack(register_entry->address, regmap->register_address_width, regmap->register_address_byte_order, address_data);

	common_iic_status_t const iic_status =
	    common_iic_write_read(iic_interface, slave_address, address_data, (uint32_t)regmap->register_address_width, value_data, (uint32_t)regmap->register_data_width);
	if (iic_status == COMMON_IIC_SUCCESS)
	{
		*value = common_iic_regmap_unpack(value_data, regmap->register_data_width, regmap->register_data_byte_order) & register_entry->mask;
	}

	return (common_iic_regmap_status_t)iic_status;
}

static common_iic_regmap_status_t common_iic_regmap_write_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t value)
{
	const common_iic_regmap_t *const regmap = (const common_iic_regmap_t *)context;
	if ((regmap == NULL) || (iic_interface == NULL))
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if (register_id >= regmap->register_count)
	{
		return COMMON_IIC_REGMAP_INVALID_REGISTER;
	}

	const common_iic_regmap_register_t *const register_entry = &regmap->registers[register_id];
	if ((register_entry->access != COMMON_IIC_REGMAP_ACCESS_WRITE_ONLY) && (register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_WRITE))
	{
		return COMMON_IIC_REGMAP_ACCESS_DENIED;
	}

	uint32_t const data_width_mask = UINT32_MAX >> ((COMMON_IIC_REGMAP_MAX_WIDTH - (uint32_t)regmap->register_data_width) * 8U);
	if (((value & ~data_width_mask) != 0U) || ((value & ~register_entry->mask) != 0U))
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	uint8_t tx_data[COMMON_IIC_REGMAP_MAX_WIDTH * 2U];
	common_iic_regmap_pack(register_entry->address, regmap->register_address_width, regmap->register_address_byte_order, tx_data);
	common_iic_regmap_pack(value, regmap->register_data_width, regmap->register_data_byte_order, &tx_data[regmap->register_address_width]);

	return (common_iic_regmap_status_t)common_iic_write(iic_interface, slave_address, tx_data, (uint32_t)regmap->register_address_width + (uint32_t)regmap->register_data_width);
}

static common_iic_regmap_status_t common_iic_regmap_update_bits_implementation(void *context, common_iic_interface_t *iic_interface, uint32_t slave_address, uint32_t register_id, uint32_t mask, uint32_t value)
{
	const common_iic_regmap_t *const regmap = (const common_iic_regmap_t *)context;
	if ((regmap == NULL) || (iic_interface == NULL))
	{
		return COMMON_IIC_REGMAP_INVALID_PARAM;
	}

	if (register_id >= regmap->register_count)
	{
		return COMMON_IIC_REGMAP_INVALID_REGISTER;
	}

	const common_iic_regmap_register_t *const register_entry = &regmap->registers[register_id];
	if (register_entry->access != COMMON_IIC_REGMAP_ACCESS_READ_WRITE)
	{
		return COMMON_IIC_REGMAP_ACCESS_DENIED;
	}

	uint32_t old_value;
	common_iic_regmap_status_t const read_status = common_iic_regmap_read_implementation(context, iic_interface, slave_address, register_id, &old_value);
	if (read_status != COMMON_IIC_REGMAP_SUCCESS)
	{
		return read_status;
	}

	uint32_t const effective_mask = mask & register_entry->mask;
	uint32_t const new_value = (old_value & ~effective_mask) | (value & effective_mask);
	return common_iic_regmap_write_implementation(context, iic_interface, slave_address, register_id, new_value);
}
