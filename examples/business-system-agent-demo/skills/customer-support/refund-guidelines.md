# Refund Guidelines

Refund requests should be handled with the following business policy:

- Enterprise customers can receive a refund or account credit when the order is inside the refund window.
- If `refund_policy_check` returns `approvalRequired: true`, create a ticket with priority `high`.
- If the customer has open tickets, mention that the new action will be linked to the existing customer operations queue.
- Do not promise refunds without the `refund_policy_check` result.
