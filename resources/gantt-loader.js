// Agiladmin - spreadsheet based time and budget administration

// Copyright (C) 2016-2019 Dyne.org foundation

// Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.

// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

gantt.config.open_tree_initially = true;
gantt.config.scale_unit = "month";
gantt.config.duration_unit = "month";
gantt.config.date_scale = "%M";
gantt.config.scale_height = 30;
gantt.config.subscales = [{unit: "year", step: 1, date: "%Y" }];
gantt.config.task_height = 20;
gantt.config.row_height = 30;
gantt.config.types.root = "project-task";
gantt.config.types.subproject = "subproject";
gantt.config.readonly = true;
gantt.config.lightbox.subproject_sections = gantt.config.lightbox.sections;
gantt.config.lightbox.project_sections = [
    {name: "task", height: 70, map_to: "id", type: "textarea", focus: true},
    {name: "pm", type: "text", map_to: "pm", readonly: true}
];

var date_to_str = gantt.date.date_to_str(gantt.config.task_date);
// var today = new Date(2017, 10, 12);
gantt.addMarker({
    start_date: today,
    css: "today",
    text: "Today",
    title:"Today: "+ date_to_str(today)
});

// no lightbox at all
gantt.attachEvent("onBeforeLightbox", function(id) { return false; });

// set initial values based on task type
function defaultValues(task) {
    var text = "",
        index = gantt.getChildren(task.parent || gantt.config.root_id).length + 1,
        types = gantt.config.types;

    switch (task.type) {
    case types.project:
        text = "Project";
        break;
    case types.subproject:
        text = 'Subproject';
        break;
    default:
        text = 'Task';
        break;
    }
    task.text = text + " #" + index;
    return;
}

gantt.attachEvent("onTaskCreated", function (task) {
    var parent = task.parent,
        types = gantt.config.types,
        level = 0;

    if (parent == gantt.config.root_id || !parent) {
        level = 0;
    } else {
        level = gantt.getTask(task.parent).$level + 1;
    }
    //assign task type based on task level
    switch (level) {
    case 0:
        task.type = types.project;
        break;
    case 1:
        task.type = types.subproject;
        break;
    default:
        task.type = types.task;
        break;
    }

    defaultValues(task);
    return true;
});


gantt.templates.task_text=function(start,end,task) {
    return "<span><b>"+task.id+"</b></span>"; };
gantt.templates.leftside_text = function(start, end, task) {
    return "<span>"+task.pm+" PM</span>"; };
gantt.templates.rightside_text = function(start, end, task) {
    return "<span>"+task.pm+" PM</span>"; };
gantt.templates.progress_text = function(start, end, task){
    return "<span>"+Math.round(task.progress*100)+ "% </span>"; };

//css template for each task type
gantt.templates.task_class = gantt.templates.grid_row_class = function (start, end, task) {
    switch (task.type) {
    case gantt.config.types.project:
        return 'project-task';
        break;
    case gantt.config.types.subproject:
        return 'phase-task';
        break;
    default:
        return 'regular-task';
        break;
    }
};

function limitMoveLeft(task, limit){
    var dur = task.end_date - task.start_date;
    task.end_date = new Date(limit.end_date);
    task.start_date = new Date(+task.end_date - dur);
}
function limitMoveRight(task, limit){
    var dur = task.end_date - task.start_date;
    task.start_date = new Date(limit.start_date);
    task.end_date = new Date(+task.start_date + dur);
}

function limitResizeLeft(task, limit){
    task.end_date = new Date(limit.end_date);
}
function limitResizeRight(task, limit){
    task.start_date = new Date(limit.start_date)
}

gantt.attachEvent("onTaskDrag", function(id, mode, task, original, e){
    var parent = task.parent ? gantt.getTask(task.parent) : null,
        children = gantt.getChildren(id),
        modes = gantt.config.drag_mode;

    var limitLeft = null,
        limitRight = null;

    if(!(mode == modes.move || mode == modes.resize)) return;

    if(mode == modes.move){
        limitLeft = limitMoveLeft;
        limitRight = limitMoveRight;
    }else if(mode == modes.resize){
        limitLeft = limitResizeLeft;
        limitRight = limitResizeRight;
    }

    //check parents constraints
    if(parent && +parent.end_date < +task.end_date){
        limitLeft(task, parent);
    }
    if(parent && +parent.start_date > +task.start_date){
        limitRight(task, parent);
    }
    //check children constraints
    for(var i=0; i < children.length; i++){
        var child = gantt.getTask(children[i]);
        if(+task.end_date < +child.end_date){
            limitLeft(task, child);
        }else if(+task.start_date > +child.start_date){
            limitRight(task, child)
        }
    }


});



/* ZOOM */



function toggleMode(toggle) {
    toggle.enabled = !toggle.enabled;
    if (toggle.enabled) {
        toggle.innerHTML = "Scale to Default";
        //Saving previous scale state for future restore
        saveConfig();
        zoomToFit();
    } else {

        toggle.innerHTML = "Scale to Fit";
        //Restore previous scale state
        restoreConfig();
        gantt.render();
    }
}

var cachedSettings = {};
function saveConfig() {
    var config = gantt.config;
    cachedSettings = {};
    cachedSettings.scale_unit = config.scale_unit;
    cachedSettings.date_scale = config.date_scale;
    cachedSettings.step = config.step;
    cachedSettings.subscales = config.subscales;
    cachedSettings.template = gantt.templates.date_scale;
    cachedSettings.start_date = config.start_date;
    cachedSettings.end_date = config.end_date;
}
function restoreConfig() {
    applyConfig(cachedSettings);
}

function applyConfig(config, dates) {
    gantt.config.scale_unit = config.scale_unit;
    if (config.date_scale) {
        gantt.config.date_scale = config.date_scale;
        gantt.templates.date_scale = null;
    }
    else {
        gantt.templates.date_scale = config.template;
    }

    gantt.config.step = config.step;
    gantt.config.subscales = config.subscales;

    if (dates) {
        gantt.config.start_date = gantt.date.add(dates.start_date, -1, config.unit);
        gantt.config.end_date = gantt.date.add(gantt.date[config.unit + "_start"](dates.end_date), 2, config.unit);
    } else {
        gantt.config.start_date = gantt.config.end_date = null;
    }
}



function zoomToFit() {
    var project = gantt.getSubtaskDates(),
        areaWidth = gantt.$task.offsetWidth;

    for (var i = 0; i < scaleConfigs.length; i++) {
        var columnCount = getUnitsBetween(project.start_date, project.end_date, scaleConfigs[i].unit, scaleConfigs[i].step);
        if ((columnCount + 2) * gantt.config.min_column_width <= areaWidth) {
            break;
        }
    }

    if (i == scaleConfigs.length) {
        i--;
    }

    applyConfig(scaleConfigs[i], project);
    gantt.render();
}

// get number of columns in timeline
function getUnitsBetween(from, to, unit, step) {
    var start = new Date(from),
        end = new Date(to);
    var units = 0;
    while (start.valueOf() < end.valueOf()) {
        units++;
        start = gantt.date.add(start, step, unit);
    }
    return units;
}

//Setting available scales
var scaleConfigs = [
    // minutes
    { unit: "minute", step: 1, scale_unit: "hour", date_scale: "%H", subscales: [
        {unit: "minute", step: 1, date: "%H:%i"}
    ]
    },
    // hours
    { unit: "hour", step: 1, scale_unit: "day", date_scale: "%j %M",
      subscales: [
          {unit: "hour", step: 1, date: "%H:%i"}
      ]
    },
    // days
    { unit: "day", step: 1, scale_unit: "month", date_scale: "%F",
      subscales: [
          {unit: "day", step: 1, date: "%j"}
      ]
    },
    // weeks
    {unit: "week", step: 1, scale_unit: "month", date_scale: "%F",
     subscales: [
         {unit: "week", step: 1, template: function (date) {
             var dateToStr = gantt.date.date_to_str("%d %M");
             var endDate = gantt.date.add(gantt.date.add(date, 1, "week"), -1, "day");
             return dateToStr(date) + " - " + dateToStr(endDate);
         }}
     ]},
    // months
    { unit: "month", step: 1, scale_unit: "year", date_scale: "%Y",
      subscales: [
          {unit: "month", step: 1, date: "%M"}
      ]},
    // quarters
    { unit: "month", step: 3, scale_unit: "year", date_scale: "%Y",
      subscales: [
          {unit: "month", step: 3, template: function (date) {
              var dateToStr = gantt.date.date_to_str("%M");
              var endDate = gantt.date.add(gantt.date.add(date, 3, "month"), -1, "day");
              return dateToStr(date) + " - " + dateToStr(endDate);
          }}
      ]},
    // years
    {unit: "year", step: 1, scale_unit: "year", date_scale: "%Y",
     subscales: [
         {unit: "year", step: 5, template: function (date) {
             var dateToStr = gantt.date.date_to_str("%Y");
             var endDate = gantt.date.add(gantt.date.add(date, 5, "year"), -1, "day");
             return dateToStr(date) + " - " + dateToStr(endDate);
         }}
     ]},
    // decades
    {unit: "year", step: 10, scale_unit: "year", template: function (date) {
        var dateToStr = gantt.date.date_to_str("%Y");
        var endDate = gantt.date.add(gantt.date.add(date, 10, "year"), -1, "day");
        return dateToStr(date) + " - " + dateToStr(endDate);
    },
     subscales: [
         {unit: "year", step: 100, template: function (date) {
             var dateToStr = gantt.date.date_to_str("%Y");
             var endDate = gantt.date.add(gantt.date.add(date, 100, "year"), -1, "day");
             return dateToStr(date) + " - " + dateToStr(endDate);
         }}
     ]}
];


