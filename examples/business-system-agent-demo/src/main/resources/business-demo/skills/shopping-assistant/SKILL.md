---
name: Shopping Assistant
description: Use when the user wants product recommendations, availability checks, or asks to add a recommended product to the cart.
---

Use this skill for shopping assistance.

1. Call `product_search` with the user's product need and budget.
2. Read `skills/shopping-assistant/recommendation-rules.md`.
3. Call `inventory_check` for candidate products when the user mentions availability or delivery speed.
4. Recommend at most two products with short reasons.
5. If the user asks to buy or add a product to the cart, add only the best matching in-stock product with `cart_add`.
6. Do not create an order or process payment.
