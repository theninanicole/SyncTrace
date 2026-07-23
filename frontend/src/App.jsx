import { useEffect, useRef, useState } from 'react';
import { supabase } from './supabaseClient';
import { verifyStudentWithBackend } from './api';
import Login from './pages/auth/LoginPage';
import Home from './Home';
import TeacherDashboard from './pages/teacher/TeacherDashboardPage';
import SyncTraceDashboardPage from './synctrace';
import LoadingScreen from './components/common/LoadingScreen';
import {
  buildSyncTracePath,
  isSyncTracePath,
  navigatePath,
  pathToView,
} from './synctrace/routes';

function App() {
  const [studentData, setStudentData]   = useState(null);
  const [authError, setAuthError]       = useState('');
  const [isVerifying, setIsVerifying]   = useState(false);
  const [showSyncTrace, setShowSyncTrace] = useState(() => isSyncTracePath());
  const [isInitializing, setIsInitializing] = useState(true);

  const isVerifiedRef  = useRef(false);
  const pendingErrorRef = useRef('');

  async function verifySession(currentSession) {
    if (!currentSession) return;
    if (isVerifiedRef.current) return;

    pendingErrorRef.current = '';
    setAuthError('');
    setIsVerifying(true);

    try {
      const googleEmail = currentSession.user.email;
      const data = await verifyStudentWithBackend(googleEmail);
      isVerifiedRef.current = true;
      setStudentData(data);
    } catch (error) {
      console.error('Verification failed:', error);

      const msg = error.message || '';
      const isUnauthorized =
        msg.includes('403') ||
        msg.toLowerCase().includes('unauthorized') ||
        msg.toLowerCase().includes('not on the class') ||
        msg.toLowerCase().includes('forbidden') ||
        msg.toLowerCase().includes('access denied');

      const errorToShow = isUnauthorized
        ? 'Unauthorized: Your Google account is not on the Class Allowlist. Please contact your professor.'
        : msg || 'An unexpected error occurred. Please try again.';

      pendingErrorRef.current = errorToShow;
      isVerifiedRef.current = false;

      await supabase.auth.signOut();
      setStudentData(null);
      setAuthError(errorToShow);
    } finally {
      setIsVerifying(false);
    }
  }

  useEffect(() => {
    supabase.auth.getSession().then(({ data: { session } }) => {
      if (session) {
        verifySession(session).finally(() => setIsInitializing(false));
      } else {
        setIsInitializing(false);
      }
    });
  }, []);

  useEffect(() => {
    const { data: { subscription } } = supabase.auth.onAuthStateChange(async (event, currentSession) => {

      if (event === 'SIGNED_IN' && currentSession) {
        if (isVerifiedRef.current) return;
        await verifySession(currentSession);
      }

      if (event === 'SIGNED_OUT') {
        isVerifiedRef.current = false;
        setStudentData(null);
        setShowSyncTrace(false);
        if (isSyncTracePath()) {
          window.history.replaceState(null, '', '/');
        }

        if (pendingErrorRef.current) {
          setAuthError(pendingErrorRef.current);
        } else {
          setAuthError('');
        }
      }
    });

    return () => subscription.unsubscribe();
  }, []);

  // Keep SyncTrace shell in sync with the browser URL (back/forward + pushState).
  useEffect(() => {
    function syncRoute() {
      const onSyncTrace = isSyncTracePath();
      setShowSyncTrace(onSyncTrace);

      if (onSyncTrace) {
        const loc = pathToView(window.location.pathname, window.location.search);
        if (loc?.redirectTo) {
          window.history.replaceState(null, '', loc.redirectTo);
        }
      }
    }

    syncRoute();
    window.addEventListener('popstate', syncRoute);
    return () => window.removeEventListener('popstate', syncRoute);
  }, []);

  function openSyncTrace() {
    navigatePath(buildSyncTracePath('overview'));
    setShowSyncTrace(true);
  }

  function closeSyncTrace() {
    if (isSyncTracePath()) {
      navigatePath('/');
    }
    setShowSyncTrace(false);
  }

  if (isInitializing || isVerifying) {
    return (
      <LoadingScreen
        title={isInitializing ? 'Loading...' : 'Verifying Credentials...'}
        subtitle={isInitializing ? 'Restoring your session' : 'Syncing with Google Services'}
      />
    );
  }

  return (
    <div className="app-container">
      {studentData ? (
        studentData.role === 'TEACHER' ? (
          showSyncTrace ? (
            <SyncTraceDashboardPage onBack={closeSyncTrace} />
          ) : (
            <TeacherDashboard user={studentData} onOpenSyncTrace={openSyncTrace} />
          )
        ) : (
          <Home studentData={studentData} />
        )
      ) : (
        <Login authError={authError} />
      )}
    </div>
  );
}

export default App;
