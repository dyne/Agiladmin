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
gantt.config.lightbox.subproject_sections = gantt.config.lightbox.sections;
gantt.config.lightbox.project_sections = [
    {name: "description", height: 70, map_to: "text", type: "textarea", focus: true},
    {name: "time", type: "duration", map_to: "auto", readonly: true}
];

var date_to_str = gantt.date.date_to_str(gantt.config.task_date);
var today = new Date(2017, 10, 12);
gantt.addMarker({
    start_date: today,
    css: "today",
    text: "Today",
    title:"Today: "+ date_to_str(today)
});

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

gantt.templates.progress_text = function(start, end, task){
	return "<span>"+Math.round(task.progress*100)+ "% </span>";
};

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
