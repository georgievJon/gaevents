package com.clouway.asynctaskscheduler.gae;

import com.clouway.asynctaskscheduler.spi.AsyncEvent;
import com.clouway.asynctaskscheduler.spi.AsyncTaskOptions;
import com.clouway.asynctaskscheduler.spi.AsyncTaskScheduler;
import com.clouway.asynctaskscheduler.spi.EventTransport;
import com.google.appengine.api.taskqueue.TaskAlreadyExistsException;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;

/**
 * Represents a service that adds  task queues that runs asynchronous tasks
 * <p/>
 * <p/>
 * example usage :
 * taskScheduler
 * .add(task(AsyncTaskImpl)
 * .param("name", "param value"))
 * .now();
 * <p/>
 *
 * Task scheduler also could be used for implementation of fork-join queues by using named tasks. Here is an
 * example how this could be accomplished:
 * <pre>
 *   Long id = datastore.put(new MyWork("revenues", 10)).getId();
 *   taskScheduler.add(task(RevenueSummarizer).named("revenues-2011-10-10 12:30:00").param("revenueDate", "2011-10-10 12:30:00")).now();
 *   ....
 * </pre>
 * and in the RevenueSummarizer task you can join all of the inserted items for the provided minute.
 * <p/>
 * AsyncTaskImpl implements {@link com.clouway.asynctaskscheduler.spi.AsyncTask}
 * <p/>
 * taskScheduler
 * .add(event(AsyncEventImpl))
 * .now();
 * <p/>
 * AsyncEventImpl implements {@link com.clouway.asynctaskscheduler.spi.AsyncEvent}
 *
 * @author Mihail Lesikov (mlesikov@gmail.com)
 */
public class TaskQueueAsyncTaskScheduler implements AsyncTaskScheduler {
  public static final String TASK_QUEUE = "taskQueue";
  public static final String EVENT = "event";
  public static final String EVENT_AS_JSON = "eventJson";
  public static final String LISTENER = "listener";
  public static final String HANDLER = "handler";

  private List<AsyncTaskOptions> taskOptions;

  private final EventTransport eventTransport;
  private final CommonParamBinder commonParamBinder;
  private TaskApplier taskApplier;


  @Inject
  public TaskQueueAsyncTaskScheduler(EventTransport eventTransport, CommonParamBinder commonParamBinder, TaskApplier taskApplier) {
    this.eventTransport = eventTransport;
    this.commonParamBinder = commonParamBinder;
    this.taskApplier = taskApplier;
    this.taskOptions = Lists.newArrayList();
  }


  /**
   * builds the task queue form the task options
   */
  public void now() {

    Map<String, String> commonParams = new HashMap<String, String>();
    commonParamBinder.bindCommonParams(commonParams);

    for (AsyncTaskOptions taskOption : taskOptions) {

      /**
       * We need to add and common parameters, before we schedule
       * task for execution
       */
      for (String paramKey : commonParams.keySet()) {
        taskOption.param(paramKey, commonParams.get(paramKey));
      }

      if (taskOption.isEventTaskOption()) {

        addEventTaskQueueOption(taskOption);

      } else {

        addTaskQueue(taskOption);

      }

    }
  }

  /**
   * Adds Task queue options for an async even({@link com.clouway.asynctaskscheduler.spi.AsyncEvent})
   *
   * @param taskOptions
   */
  private void addEventTaskQueueOption(AsyncTaskOptions taskOptions) {

    TaskOptions task = createEventTaskOptions(taskOptions);


    setExecutionDate(taskOptions, task);

    String queueName = getQueueName(taskOptions.getEvent().getClass(), taskOptions.getEvent().getAssociatedHandlerClass(), taskOptions.getEventListenerClass());

    addTaskToTheQueue(taskOptions, queueName, task);

  }

  /**
   * Creates event task options
   *
   * @param taskOptions
   * @return
   */
  private TaskOptions createEventTaskOptions(AsyncTaskOptions taskOptions) {
    TaskOptions task;
    task = withUrl(TaskQueueAsyncTaskExecutorServlet.URL);

    //main task queue parameter
    task.param(EVENT, taskOptions.getEvent().getClass().getName());

    String eventAsJson = getAsyncEventAsJson(taskOptions.getEvent());

    try {

      String encodedEventAsJson = URLEncoder.encode(eventAsJson,"UTF-8");
      task.param(EVENT_AS_JSON, encodedEventAsJson);

    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

    if (taskOptions.getEventListenerClass() != null){
      task.param(LISTENER, taskOptions.getEventListenerClass().getSimpleName());
    }

    if (taskOptions.getEventHandlerClass() != null){
      task.param(HANDLER, taskOptions.getEventHandlerClass().getSimpleName());
    }

    //adds all other parameters
    task = addParams(task, taskOptions.getParams());
    return task;
  }

  private String getAsyncEventAsJson(AsyncEvent event) {

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    eventTransport.out(event.getClass(), event, outputStream);

    String eventAsJson = "";

    try {
      eventAsJson = outputStream.toString("UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }

    try {
      outputStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return eventAsJson;
  }

  /**
   * Adds the task queue formed form the {@link com.clouway.asynctaskscheduler.spi.AsyncTaskOptions}
   *
   * @param taskOptions the AsyncTaskOptions
   */
  private void addTaskQueue(AsyncTaskOptions taskOptions) {

    TaskOptions task = createTaskOptions(taskOptions);

    setExecutionDate(taskOptions, task);

    String  queueName = getQueueName(taskOptions.getAsyncTask());

    addTaskToTheQueue(taskOptions, queueName, task);

  }

  private void addTaskToTheQueue(AsyncTaskOptions taskOptions, String queue, TaskOptions task) {
    try {

      taskApplier.apply(task, queue, taskOptions.isTransactionless());

    } catch (TaskAlreadyExistsException e) {
      // Fan-In magic goes here
    } catch (TransientFailureException e){
      //retry adding the task to the queue

      taskApplier.apply(task, queue, taskOptions.isTransactionless());

    }
  }

  /**
   * Sets the execution date to the task queue
   *
   * @param taskOptions
   * @param task
   */
  private void setExecutionDate(AsyncTaskOptions taskOptions, TaskOptions task) {
    if (taskOptions.getDelayMills() > 0) {

      task.countdownMillis(taskOptions.getDelayMills());

    } else if (taskOptions.getExecutionDateMills() > 0) {

      task.etaMillis(taskOptions.getExecutionDateMills());

    }
  }

  /**
   * Creates a {@link TaskOptions} objects formed form the given {@link com.clouway.asynctaskscheduler.spi.AsyncTaskOptions}
   *
   * @param taskOptions the given {@link com.clouway.asynctaskscheduler.spi.AsyncTaskOptions}
   * @return
   */
  private TaskOptions createTaskOptions(AsyncTaskOptions taskOptions) {
    TaskOptions task;
    task = withUrl(TaskQueueAsyncTaskExecutorServlet.URL);

    //main task queue parameter
    task.param(TASK_QUEUE, taskOptions.getAsyncTaskAsString());

    //adds all other parameters
    task = addParams(task, taskOptions.getParams());

    // task was named? so we have to add it as name and we have to emit
    // the TaskAlreadyExistsException when task is added.
    if (taskOptions.getTaskName() != null) {
      task.taskName(taskOptions.getTaskName());
    }

    return task;
  }

  /**
   * Adds new {@link com.clouway.asynctaskscheduler.spi.AsyncTaskOptions} object
   *
   * @param asyncTaskOptions
   * @return
   */
  public AsyncTaskScheduler add(AsyncTaskOptions asyncTaskOptions) {
    taskOptions.add(asyncTaskOptions);
    return this;
  }

  /**
   * Adds many {@link com.clouway.asynctaskscheduler.spi.AsyncTaskOptions} objects
   *
   * @param asyncTaskOptions
   * @return
   */
  public AsyncTaskScheduler add(AsyncTaskOptions... asyncTaskOptions) {
    for (AsyncTaskOptions asyncTaskOption : asyncTaskOptions) {
      taskOptions.add(asyncTaskOption);
    }
    return this;
  }

  /**
   * Adds parameters to the given {@link com.google.appengine.api.taskqueue.TaskOptions}
   *
   * @param task
   * @param params
   * @return
   */
  private TaskOptions addParams(TaskOptions task, Map<String, String> params) {

    for (String key : params.keySet()) {
      task.param(key, params.get(key));
    }

    return task;
  }

  /**
   * Gets the Task Queue from the given name or the Default Task Queue
   *
   * @param asyncJobClasses
   * @return
   */
  private String  getQueueName(Class... asyncJobClasses) {
    QueueName queueName = null;

    for (Class asyncJobClass : asyncJobClasses) {
      if (asyncJobClass != null) {
        QueueName classQueueName = (QueueName) asyncJobClass.getAnnotation(QueueName.class);
        if (classQueueName != null && !Strings.isNullOrEmpty(classQueueName.name())) {
          queueName = classQueueName;
        } else {
          // little acrobatics so we can preserve the old behavior
          classQueueName = (QueueName) asyncJobClasses[0].getAnnotation(QueueName.class);
          if (classQueueName != null && !Strings.isNullOrEmpty(classQueueName.name())) {
            queueName = classQueueName;
          } else {
            queueName = null;
          }
        }
      }
    }

    if(queueName!= null){
      return queueName.name();
    }

    return "";
  }
}
