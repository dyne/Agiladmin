
# Bug fix

- error after upload of timesheet
- disappearance T5.1 on DECODE upon ts upload
- mark logged username in git history
- remove email passwords from configuration!!!!

# Manuela's feedback and reminders

- bottoni di bootstrap non clickabili con tasto destro per new tab
- prima pagina se loggato deve dare la dashboard e non "login"
- un search per la lista dei nomi per quando diventano tanti
- logo e link di dyne metterlo in alto
- nel footer mettere i nostri contatti
- newcomers da capire dove mettere con il design
- lista persone con accanto link agli anni precedenti
  e anche ai progetti in cui partecipa <- nuova view!
	  view della persona sul progetto (solo per quel progetto)
	  con una view praticamente uguale a quella sulla persona
		(reuse person_view)
- nella person_view al posto dello zero 0 mettere un trattino -
  per quelle voci che sono VOL
- Yearly totals nel person_view
  aggiungere sotto anche la specifica sui totali per progetto
  cioe' totale annuale e anche sui progetti
- Project_view
  aggiungere accanto al progetto i singoli anni
  anni farli clickabili che visualizzino solo i dati di quell'anno
  un po' come per le persone (reuse project_view) passandogli un
  dataset limitato a quell'anno



- previous year button also on top of person's page
- reformulate monthly report to exclude voluntary hours
- button to set month as paid and lock total modifications
- per-project voluntary hours separated from other hours
  they are all mixed with paid hours
- per-project yearly totals



# General Improvements

- push on succesfull upload of timesheet
- save cached parsing of timesheet in clj-storage nippy
X add user authentication via just-auth
- show unused tasks into project listings
- yearly voluntary hours per person

# Timesheet Improvements

- validity check on a set of tags ('vol' is the only one right now)
- validity check on existing tasks for each project
- download of latest timesheet per user
- export to csv, json and html per project
- upload can be free, but if modifying things in the past should be suspended
  and pend approval from financial admin / or can be done just by financial admin


# JS components of interest

- https://github.com/plouc/nivo (D3 based dataviz graphs)

- https://dhtmlx.com/docs/products/dhtmlxSuite/

- https://dhtmlx.com/docs/products/dhtmlxGantt/

- https://dhtmlx.com/docs/products/dhtmlxScheduler/

- https://dhtmlx.com/docs/products/dhtmlxChart/

- https://github.com/webix-hub/

- https://webix.com/filemanager/

- https://webix.com/pivot/chart.html

