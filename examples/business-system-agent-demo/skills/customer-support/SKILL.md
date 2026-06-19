---
name: Customer Support Workflow
description: Use when handling customer support, refund, account, or follow-up ticket questions in the business system.
---

Use this workflow for customer operations requests.

1. Call `customer_lookup` before answering account or customer-specific questions.
2. If the user asks about refund eligibility, call `refund_policy_check` with the customer id and order id.
3. If a follow-up case should be tracked, call `ticket_create` with a short business summary and priority.
4. Read `skills/customer-support/refund-guidelines.md` when the user asks for refund handling details.
5. Answer with the customer state, policy result, created ticket if any, and the next business action.
