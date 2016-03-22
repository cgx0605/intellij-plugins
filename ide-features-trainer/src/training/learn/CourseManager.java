package training.learn;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import training.commands.BadCommandException;
import training.editor.EduEditor;
import training.editor.EduEditorProvider;
import training.editor.eduUI.EduPanel;
import training.learn.dialogs.SdkModuleProblemDialog;
import training.learn.dialogs.SdkProjectProblemDialog;
import training.learn.exceptons.*;
import training.learn.log.GlobalLessonLog;
import training.ui.LearnToolWindowFactory;
import training.util.GenerateCourseXml;
import training.util.MyClassLoader;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

/**
 * Created by karashevich on 11/03/15.
 */
@State(
        name = "TrainingPluginCourses",
        storages = {
                @Storage(
                        file = StoragePathMacros.APP_CONFIG + "/trainingPlugin.xml"
                )
        }
)
public class CourseManager implements PersistentStateComponent<CourseManager.State> {

    private Project eduProject;
    private EduPanel myEduPanel;
    final public static String EDU_PROJECT_NAME = "EduProject";

    CourseManager() {
        if (myState.courses == null || myState.courses.size() == 0) try {
            initCourses();
        } catch (JDOMException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        } catch (BadCourseException e) {
            e.printStackTrace();
        } catch (BadLessonException e) {
            e.printStackTrace();
        }
    }

    private HashMap<Course, VirtualFile> mapCourseVirtualFile = new HashMap<Course, VirtualFile>();
    private State myState = new State();

    public static CourseManager getInstance() {
        return ServiceManager.getService(CourseManager.class);
    }

    public void initCourses() throws JDOMException, IOException, URISyntaxException, BadCourseException, BadLessonException {
        Element coursesRoot = Course.getRootFromPath(GenerateCourseXml.COURSE_ALLCOURSE_FILENAME);
        for (Element element : coursesRoot.getChildren()) {
            if (element.getName().equals(GenerateCourseXml.COURSE_TYPE_ATTR)) {
                String courseFilename = element.getAttribute(GenerateCourseXml.COURSE_NAME_ATTR).getValue();
                final Course course = Course.initCourse(courseFilename);
                addCourse(course);
            }
        }
    }


    @Nullable
    public Course getCourseById(String id) {
        final Course[] courses = getCourses();
        if (courses == null || courses.length == 0) return null;

        for (Course course : courses) {
            if (course.getId().toUpperCase().equals(id.toUpperCase())) return course;
        }
        return null;
    }

    public void registerVirtualFile(Course course, VirtualFile virtualFile) {
        mapCourseVirtualFile.put(course, virtualFile);
    }

    public boolean isVirtualFileRegistered(VirtualFile virtualFile) {
        return mapCourseVirtualFile.containsValue(virtualFile);
    }

    public void unregisterVirtaulFile(VirtualFile virtualFile) {
        if (!mapCourseVirtualFile.containsValue(virtualFile)) return;
        for (Course course : mapCourseVirtualFile.keySet()) {
            if (mapCourseVirtualFile.get(course).equals(virtualFile)) {
                mapCourseVirtualFile.remove(course);
                return;
            }
        }
    }

    public void unregisterCourse(Course course) {
        mapCourseVirtualFile.remove(course);
    }


    public synchronized void openLesson(Project project, final @Nullable Lesson lesson) throws BadCourseException, BadLessonException, IOException, FontFormatException, InterruptedException, ExecutionException, LessonIsOpenedException {

        try {

            assert lesson != null;
            checkEnvironment(project, lesson.getCourse());

            if (lesson.isOpen()) throw new LessonIsOpenedException(lesson.getName() + " is opened");

            //If lesson doesn't have parent course
            if (lesson.getCourse() == null)
                throw new BadLessonException("Unable to open lesson without specified course");
            final Project myProject = project;
            final String scratchFileName = "Learning...";
            final VirtualFile vf = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                @Override
                public VirtualFile compute() {
                    try {
                        if (lesson.getCourse().courseType == Course.CourseType.SCRATCH) {
                            return getScratchFile(myProject, lesson, scratchFileName);
                        } else {
                            if (!initEduProject(myProject)) return null;
                            return getFileInEduProject(lesson);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    }
                }
            });
            if (vf == null) return; //if user aborts opening lesson in EduProject or Virtual File couldn't be computed
            if (lesson.getCourse().courseType != Course.CourseType.SCRATCH) project = eduProject;

            //open next lesson if current is passed
            final Project currentProject = project;

            lesson.onStart();

            lesson.addLessonListener(new LessonListenerAdapter() {
                @Override
                public void lessonNext(Lesson lesson) throws BadLessonException, ExecutionException, IOException, FontFormatException, InterruptedException, BadCourseException, LessonIsOpenedException {
                    if (lesson.getCourse() == null) return;

                    if (lesson.getCourse().hasNotPassedLesson()) {
                        Lesson nextLesson = lesson.getCourse().giveNotPassedAndNotOpenedLesson();
                        if (nextLesson == null)
                            throw new BadLessonException("Unable to obtain not passed and not opened lessons");
                        openLesson(currentProject, nextLesson);
                    }
                }
            });

            final String target;
            if (lesson.getTargetPath() != null) {
                InputStream is = MyClassLoader.getInstance().getResourceAsStream(lesson.getCourse().getAnswersPath() + lesson.getTargetPath());
                if (is == null) throw new IOException("Unable to get answer for \"" + lesson.getName() + "\" lesson");
                target = new Scanner(is).useDelimiter("\\Z").next();
            } else {
                target = null;
            }


            //Dispose balloon while scratch file is closing. InfoPanel still exists.
            project.getMessageBus().connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void fileOpened(FileEditorManager source, VirtualFile file) {
                }

                @Override
                public void fileClosed(FileEditorManager source, VirtualFile file) {
                    lesson.close();
                }

                @Override
                public void selectionChanged(FileEditorManagerEvent event) {

                }
            });

            //to start any lesson we need to do 4 steps:
            //1. open editor
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);

            //2. create LessonManager
            LessonManager lessonManager = new LessonManager(lesson, editor);

            //3. update tool window
            updateToolWindow(project);

            //4. Process lesson
            LessonProcessor.process(project, lesson, editor, target);

        } catch (NoSdkException | InvalidSdkException noSdkException){
            showSdkProblemDialog(project, noSdkException.getMessage());
        } catch (NoJavaModuleException noJavaModuleException){
            showModuleProblemDialog(project);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private VirtualFile getFileInEduProject(Lesson lesson) throws IOException {

        final VirtualFile sourceRootFile = ProjectRootManager.getInstance(eduProject).getContentSourceRoots()[0];
        String courseFileName = "Test.java";
        if (lesson.getCourse() != null) courseFileName = lesson.getCourse().getName() + ".java";


        VirtualFile courseVirtualFile = sourceRootFile.findChild(courseFileName);
        if (courseVirtualFile == null) {
            courseVirtualFile = sourceRootFile.createChildData(this, courseFileName);
        }

        registerVirtualFile(lesson.getCourse(), courseVirtualFile);
        return courseVirtualFile;
    }

    private boolean initEduProject(Project projectToClose) {
        Project myEduProject = null;

        //if projectToClose is open
        final Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        for (Project openProject : openProjects) {
            final String name = openProject.getName();
            if (name.equals(EDU_PROJECT_NAME)) {
                myEduProject = openProject;
                if (ApplicationManager.getApplication().isUnitTestMode()) return true;
            }
        }
         if (myEduProject == null || myEduProject.getProjectFile() == null) {

            if(!ApplicationManager.getApplication().isUnitTestMode()) if (!NewEduProjectUtil.showDialogOpenEduProject(projectToClose)) return false; //if user abort to open lesson in a new Project
            if(myState.eduProjectPath != null) {
                try {
                    myEduProject = ProjectManager.getInstance().loadAndOpenProject(myState.eduProjectPath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {

                try {
                    final Sdk newJdk = getJavaSdkInWA();
//                    final Sdk sdk = SdkConfigurationUtil.findOrCreateSdk(null, javaSdk);
                    myEduProject = NewEduProjectUtil.createEduProject(EDU_PROJECT_NAME, projectToClose, newJdk);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        eduProject = myEduProject;
        if (ApplicationManager.getApplication().isUnitTestMode()) return true;

        assert eduProject != null;
        assert eduProject.getProjectFile() != null;
        assert eduProject.getProjectFile().getParent() != null;
        assert eduProject.getProjectFile().getParent().getParent() != null;

        myState.eduProjectPath = eduProject.getBasePath();
        //Hide EduProject from Recent projects
        RecentProjectsManager.getInstance().removePath(eduProject.getPresentableUrl());
        return true;
    }

    @NotNull
    public Sdk getJavaSdkInWA() {
        final Sdk newJdk;
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            newJdk = ApplicationManager.getApplication().runWriteAction(new Computable<Sdk>() {
                @Override
                public Sdk compute() {
                    return getJavaSdk();
                };
            });
        } else {
            newJdk = getJavaSdk();
        }
        return newJdk;
    }

    @NotNull
    public Sdk getJavaSdk() {
        JavaSdk javaSdk = JavaSdk.getInstance();
        final String suggestedHomePath = javaSdk.suggestHomePath();
        final String versionString = javaSdk.getVersionString(suggestedHomePath);
        final Sdk newJdk = javaSdk.createJdk(javaSdk.getVersion(versionString).name(), suggestedHomePath);

        final Sdk foundJdk = ProjectJdkTable.getInstance().findJdk(newJdk.getName(), newJdk.getSdkType().getName());
        if (foundJdk == null) {
            ProjectJdkTable.getInstance().addJdk(newJdk);
        }
        return newJdk;
    }

    @Nullable
    public Project getEduProject(){
        if (eduProject == null  || eduProject.isDisposed()) {
            if (initEduProject(getCurrentProject()))
                return eduProject;
            else
                return null;
        } else {
            return eduProject;
        }
    }

    @Nullable
    public Project getCurrentProject(){
        final IdeFrame lastFocusedFrame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        if (lastFocusedFrame == null) return null;
        return lastFocusedFrame.getProject();
    }

    @NotNull
    private VirtualFile getScratchFile(@NotNull final Project project, @Nullable Lesson lesson, @NotNull final String filename) throws IOException {
        VirtualFile vf = null;
        assert lesson != null;
        assert lesson.getCourse() != null;
        if (mapCourseVirtualFile.containsKey(lesson.getCourse()))
            vf = mapCourseVirtualFile.get(lesson.getCourse());
        if (vf == null || !vf.isValid()) {
            //while course info is not stored

            //find file if it is existed
            vf = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), filename, ScratchFileService.Option.existing_only);
            if (vf != null) {
                FileEditorManager.getInstance(project).closeFile(vf);
                ScratchFileService.getInstance().getScratchesMapping().setMapping(vf, Language.findLanguageByID("JAVA"));
            }

            if (vf == null || !vf.isValid()) {
                vf = ScratchRootType.getInstance().createScratchFile(project, filename, Language.findLanguageByID("JAVA"), "");
                final VirtualFile finalVf = vf;
                if (!vf.getName().equals(filename)) {
                    ApplicationManager.getApplication().runWriteAction(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                finalVf.rename(project, filename);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }
            registerVirtualFile(lesson.getCourse(), vf);
        }
        return vf;
    }

    @Nullable
    public EduEditor getEduEditor(Project project, VirtualFile vf) {
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf);
        final FileEditor[] allEditors = FileEditorManager.getInstance(project).getAllEditors(vf);
        if (allEditors.length == 0) {
            if (!ApplicationManager.getApplication().isUnitTestMode())
                FileEditorManager.getInstance(project).openEditor(descriptor, true);
            else
//                FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                FileEditorManagerEx.getInstanceEx(project).openFile(vf, true);
                System.out.print("");

        } else {
            boolean editorIsFind = false;
            for (FileEditor curEditor : allEditors) {
                if (curEditor instanceof EduEditor) editorIsFind = true;
            }
            if (!editorIsFind) {
//              close other editors with this file
                FileEditorManager.getInstance(project).closeFile(vf);
                ScratchFileService.getInstance().getScratchesMapping().setMapping(vf, Language.findLanguageByID("JAVA"));
                if (!ApplicationManager.getApplication().isUnitTestMode())
                    FileEditorManager.getInstance(project).openEditor(descriptor, true);
                else
                    FileEditorManagerEx.getInstanceEx(project).openFileWithProviders(vf, true, false);
            }
        }
        final FileEditor selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor(vf);

        EduEditor eduEditor = null;
        final EduEditorProvider eduEditorProvider = new EduEditorProvider();
        if (selectedEditor instanceof EduEditor)
            eduEditor = (EduEditor) selectedEditor;
        else
            eduEditor = (EduEditor) eduEditorProvider.createEditor(project, vf);
//        FileEditorManagerEx.getInstance().setSelectedEditor(vf, eduEditorProvider.getEditorTypeId());
        return eduEditor;
    }

    /**
     * checking environment to start education plugin. Checking SDK.
     *
     * @param project where lesson should be started
     * @param course  education course
     * @throws OldJdkException     - if project JDK version is not enough for this course
     * @throws InvalidSdkException - if project SDK is not suitable for course
     */
    public void checkEnvironment(Project project, @Nullable Course course) throws OldJdkException, InvalidSdkException, NoSdkException, NoJavaModuleException {

        if (course == null) return;

        final Sdk projectJdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (projectJdk == null) throw new NoSdkException();

        final SdkTypeId sdkType = projectJdk.getSdkType();
        if (course.getSdkType() == Course.CourseSdkType.JAVA) {
            if (sdkType instanceof JavaSdk) {
                final JavaSdkVersion version = ((JavaSdk) sdkType).getVersion(projectJdk);
                if (version != null) {
                    if (!version.isAtLeast(JavaSdkVersion.JDK_1_6)) throw new OldJdkException(JavaSdkVersion.JDK_1_6);
                    try {
                        checkJavaModule(project);
                    } catch (NoJavaModuleException e) {
                        throw e;
                    }
                }
            } else if (sdkType.getName().equals("IDEA JDK")) {
                try {
                    checkJavaModule(project);
                } catch (NoJavaModuleException e) {
                    throw e;
                }
            } else {
                throw new InvalidSdkException("Please use at least JDK 1.6 or IDEA SDK with corresponding JDK");
            }
        }
    }

    private void checkJavaModule(Project project) throws NoJavaModuleException {

        if (ModuleManager.getInstance(project).getModules().length == 0) {
            throw new NoJavaModuleException();
        }

    }

    public void showSdkProblemDialog(Project project, String sdkMessage) {
//        final SdkProblemDialog dialog = new SdkProblemDialog(project, "at least JDK 1.6 or IDEA SDK with corresponding JDK");
        final SdkProjectProblemDialog dialog = new SdkProjectProblemDialog(project, sdkMessage);
        dialog.show();
    }

    public void showModuleProblemDialog(Project project){
        final SdkModuleProblemDialog dialog = new SdkModuleProblemDialog(project);
        dialog.show();
    }

    @Nullable
    public Lesson findLesson(String lessonName) {
        if (getCourses() == null) return null;
        for (Course course : getCourses()) {
            for (Lesson lesson : course.getLessons()) {
                if (lesson.getName() != null)
                    if (lesson.getName().toUpperCase().equals(lessonName.toUpperCase()))
                        return lesson;
            }
        }
        return null;
    }

    public void setEduPanel(EduPanel eduPanel) {
        myEduPanel = eduPanel;
    }

    public EduPanel getEduPanel(){
        return myEduPanel;
    }


    static class State {
        public final ArrayList<Course> courses = new ArrayList<Course>();
        public String eduProjectPath;
        public GlobalLessonLog globalLessonLog = new GlobalLessonLog();

        public State() {
        }


    }


    public void addCourse(Course course) {
        myState.courses.add(course);
    }

    @Nullable
    public Course[] getCourses() {
        if (myState == null) return null;
        if (myState.courses == null) return null;

        return myState.courses.toArray(new Course[myState.courses.size()]);
    }

    public GlobalLessonLog getGlobalLessonLog(){
        return myState.globalLessonLog;
    }

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState.eduProjectPath = null;
        myState.globalLessonLog = state.globalLessonLog;

        if (state.courses == null || state.courses.size() == 0) {
            try {
                initCourses();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {

            for (Course course : myState.courses) {
                if (state.courses.contains(course)) {
                    final Course courseFromPersistentState = state.courses.get(state.courses.indexOf(course));
                    for (Lesson lesson : course.getLessons()) {
                        if (courseFromPersistentState.getLessons().contains(lesson)) {
                            final Lesson lessonFromPersistentState = courseFromPersistentState.getLessons().get(courseFromPersistentState.getLessons().indexOf(lesson));
                            lesson.setPassed(lessonFromPersistentState.getPassed());
                        }
                    }
                }
            }
        }
    }

    public void updateToolWindow(@NotNull final Project project){
        final ToolWindowManager windowManager = ToolWindowManager.getInstance(project);
        String learnToolWindow = LearnToolWindowFactory.LEARN_TOOL_WINDOW;
        windowManager.getToolWindow(learnToolWindow).getContentManager().removeAllContents(false);

        LearnToolWindowFactory factory = new LearnToolWindowFactory();
        factory.createToolWindowContent(project, windowManager.getToolWindow(learnToolWindow));

    }


}