# timesheetpy

##  simple timesheet generator

timesheetpy is a small python tool that helps you generate a timesheet to be
filled out in XLSX (MS Excel) format. The format is of course, readable by
LibreOffice.

## dependencies

`python2` is required, along with the `xlsxwriter` external library. Use your
package manager or `pip` to find it and install it.

## usage

```
usage: timesheet [-h] [-sm START_MONTH] [-sy START_YEAR] [-em END_MONTH]
                 [-ey END_YEAR] [-n NAME] [-o OUTPUT_FILE]

optional arguments:
  -h, --help            show this help message and exit
  -sm START_MONTH, --start-month START_MONTH
                        starting month, ex: 9
  -sy START_YEAR, --start-year START_YEAR
                        starting year, ex: 2016
  -em END_MONTH, --end-month END_MONTH
                        ending month, ex: 11
  -ey END_YEAR, --end-year END_YEAR
                        ending year, ex: 2016
  -n NAME, --name NAME  name of person, ex: 'John Doe'
  -o OUTPUT_FILE, --output-file OUTPUT_FILE
                        output file, ex: timesheet.xlsx
```

It is not necessary to specify any argument. In that case, timesheetpy will
create a timesheet for the current month called `timesheet.xlsx`

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

