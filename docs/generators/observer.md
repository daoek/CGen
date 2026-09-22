# Observer

Fans a single call out to every subscriber implementing an existing [interface](interface.md).
Fixed capacity, no allocation, no user code — the fan-out is entirely mechanical.

```console
pinfit create observer button_events --interface button_listener --capacity 8
```

## Spec

```yaml title="button_events.observer.yaml"
kind: observer
name: button_events
description: Button event fan-out
includes: []
interface: button_listener
capacity: 8
context: []
```

| Key | Meaning |
| --- | --- |
| `interface` | The listener contract subscribers implement. Resolved by name across the project. |
| `capacity` | Maximum number of simultaneous subscribers. Becomes a `#define`. |
| `context` | Extra fields on the generated context, alongside the subscriber array and count. |

!!! warning "The listener interface must return `void`"

    Every function on the referenced interface has to return `void`. There is no sensible way to
    aggregate N subscriber return values into one, so Pinfit rejects the spec instead of picking a
    rule for you.

    If a subscriber needs to report something back, give it a separate interface that the publisher
    calls directly.

## Generated API

```c title="button_events.h"
#define BUTTON_EVENTS_CAPACITY 8u

typedef struct
{
    const button_listener_interface_t *subscribers[BUTTON_EVENTS_CAPACITY];
    uint32_t count;
} button_events_context_t;

void button_events_init(button_events_context_t *context);
bool button_events_subscribe(button_events_context_t *context, const button_listener_interface_t *subscriber);
bool button_events_unsubscribe(button_events_context_t *context, const button_listener_interface_t *subscriber);

void button_events_publish_pressed(button_events_context_t *context, uint8_t button_id);
```

One `<name>_publish_<function>()` is generated per function on the listener interface, taking the
same parameters.

### Subscribe and unsubscribe

```c
bool button_events_subscribe(button_events_context_t *context, const button_listener_interface_t *subscriber)
{
    bool pinfit_result = false;

    if ((subscriber != NULL) && (context->count < BUTTON_EVENTS_CAPACITY))
    {
        /* ... reject a duplicate, then append ... */
    }

    return pinfit_result;
}
```

- `subscribe` returns `false` when the subscriber is null, the list is **full**, or it is **already
  subscribed** — subscribing twice is not possible, so an event is never delivered twice to the
  same listener.
- `unsubscribe` returns `false` when the subscriber was not in the list. Removal swaps the last
  entry into the freed slot, so **subscriber order is not stable** across removals. Do not depend on
  notification order.

### Publish

```c
void button_events_publish_pressed(button_events_context_t *context, uint8_t button_id)
{
    uint32_t index;

    for (index = 0U; index < context->count; index++)
    {
        button_listener_pressed(context->subscribers[index], button_id);
    }
}
```

The loop calls through the listener interface's own generated dispatch wrapper, so each subscriber
still gets the null and initialisation guards the interface provides.

!!! danger "Do not subscribe or unsubscribe from inside a handler"

    Publishing walks the array by index. A subscriber that unsubscribes itself while being notified
    changes the array underneath the loop. Queue the change and apply it after the publish returns.

## Using it

```c title="main.c"
#include "button_events.h"
#include "led_blinker.h"   /* a module implementing button_listener */

static button_events_context_t buttons;
static led_blinker_context_t blinker_context;
static button_listener_interface_t blinker;

int main(void)
{
    button_events_init(&buttons);

    led_blinker_bind_button_listener(&blinker, &blinker_context);
    (void)button_events_subscribe(&buttons, &blinker);

    for (;;)
    {
        uint8_t id;
        if (button_scan(&id))
        {
            button_events_publish_pressed(&buttons, id);
        }
    }
}
```

## User regions

The fan-out itself has none — put your logic in the modules that implement the listener interface.
Only the file edges are editable: `observer.header.preamble`, `observer.header.footer`,
`observer.source.includes` and `observer.source.footer`.

## See also

- [Interface](interface.md) — define the listener contract.
- [Module](module.md) — implement it in each subscriber.
