# Future features

Ideas explicitly parked for later. Keep this file updated when a feature request is
noted down during development but not built yet.

## Exports

### Save / sync the month report to Google Sheets
The Exports page currently shows the current month's spendings as an in-app
spreadsheet-style report and saves it as a CSV to Downloads (which opens in Google
Sheets / Excel). Later: let the user export or continuously save the report straight
into a Google Sheet of their own (Google sign-in + Sheets API), so the "temporary
view" becomes a persistent, shareable sheet.

### Date memory anchors ("easily remember that day")
The report already asks Gemini to label notable spend dates (holiday/festival names
like Onam or Independence Day). Later: enrich each spend date with a personal memory
anchor so a day is easy to place, e.g. "the day after Onam" or "the next day after
you paid something big (rent ₹18,000)". These anchors could come from the user's own
transaction history (large payments, salary credit) combined with the calendar.
