(ns agiladmin.work-ports
  "Narrow application boundaries for daily work. Adapters belong to later milestones.")

(defprotocol WorkLedger
  (read-year [ledger owner-id year] "Read versioned owner/year draft and receipts.")
  (transact-month! [ledger owner-id month expected-revision request-id payload]
    "Atomically apply one month's authorized command; persist retry receipts and audit."))

(defprotocol WorkWorkbook
  (inspect-month [workbook owner month] "Return fingerprint and managed-month baseline.")
  (render-preview [workbook snapshot] "Render without mutating the authoritative workbook."))

(defprotocol WorkPublication
  (publication-status [publication owner-id month] "Read durable publication phases.")
  (publish-confirmed! [publication owner approval]
    "Publish only a browser-confirmed revision/policy/fingerprint/digest under locks."))
