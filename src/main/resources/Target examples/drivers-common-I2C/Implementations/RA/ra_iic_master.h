#ifndef RA_IIC_MASTER_H_
#define RA_IIC_MASTER_H_

#include "common_iic_I.h"
#include "osal.h"
#include "r_i2c_master_api.h"
#include <stdbool.h>

typedef struct
{
    i2c_master_instance_t *hardware_driver;
    volatile bool is_writing;
    volatile bool is_reading;
    volatile common_iic_status_t transfer_status;
    i2c_master_addr_mode_t addr_mode;
    uint32_t slave_address;
    osal_mutex_t mutex_lock;

} ra_iic_master_context_t;

common_iic_status_t ra_iic_master_create(common_iic_interface_t *interface, ra_iic_master_context_t *context, i2c_master_instance_t *hardware_driver, i2c_master_addr_mode_t addr_mode);

#endif /* RA_IIC_MASTER_H_ */
