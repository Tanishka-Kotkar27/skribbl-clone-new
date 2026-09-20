import { Navigate, Route, Routes } from 'react-router-dom';
import Home from './pages/Home';
import Room from './pages/Room';

/**
 * Route map.
 *
 * `/join/:code` exists so an invite link can drop someone straight into the
 * join form with the code already filled in — the link the host copies from the
 * lobby points here.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/join/:code" element={<Home />} />
      <Route path="/room/:code" element={<Room />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
