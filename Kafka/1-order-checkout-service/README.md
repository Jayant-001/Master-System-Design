
## Partition Key selection: userId or orderId
  - **Choose the smallest unit whose events must stay in order.** The requirement is ordering per order. userId gives you ordering per user, which is stronger than you need and costs you something.
  - **Hot keys.** Order counts per user are uneven. A reseller, a bot or a B2B account might place thousands of orders, and with userId as the key they all land on one partition and one consumer instance. orderId spreads the load almost evenly.
  - **Guest checkout.** Does a guest have a userId? If the key is null or a shared "guest" value, what happens to ordering and to load?

A case for userId would be a consumer that needs one user's events in order across different orders. Check whether any of your four services needs that. If none does, the reasoning above points to orderId.

