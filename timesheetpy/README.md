# timesheetpy

##  simple timesheet generator

timesheetpy is a small python tool that helps you generate a timesheet to be
filled out in XLSX (MS Excel) format. The format is of course, readable by
LibreOffice.

## dependencies

`python3` is required, along with the `xlsxwriter` external library. Use your
package manager or `pip` to find it and install it.

## usage

```
usage: timesheet [-h] [-Y YEAR] [-n NAME] [-N NAME] [-c ORG_NAME] [-o ORG_NAME]
                 [-l LOGO]

optional arguments:
  -h                show this help message and exit
  -Y YEAR           generate timesheets for this year, ex: 2026
  -n NAME, -N NAME  name of person, ex: 'John Doe'
  -c ORG_NAME, -o ORG_NAME
                    name displayed in header, ex: 'ACME INC.'
  -l LOGO           path to logo image, ex: dyne.png

The header logo is inserted as an image using xlsxwriter, so use a format that
xlsxwriter supports (PNG by default in this repo). SVG is not directly used.
```

It is not necessary to specify any argument. In that case, timesheetpy will
create the current year's timesheets in one file called `<year>_timesheet_timesheet.xlsx`.
If `-n`/`-N` is set (for example `John Doe`), default output will be
`<year>_timesheet_John-Doe.xlsx`.
`-Y` defaults to the current year if omitted.

Output layout note:
- The report always includes all 12 months for the selected year.
- The free text area label is placed on the same header row as `TIMESHEET` in cell `J6`.

## licensing

timesheetpy is copyright (c) 2016 by the Dyne.org Foundation

Software written by Ivan J. <parazyd@dyne.org>

This source code is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This software is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this source code. If not, see <http://www.gnu.org/licenses/>.
