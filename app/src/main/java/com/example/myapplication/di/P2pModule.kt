package com.example.myapplication.di

import com.example.myapplication.P2pCommunicationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// This module isn't strictly necessary if P2pCommunicationManager.Factory is directly usable by Hilt
// when P2pCommunicationManager uses @AssistedInject and its factory @AssistedFactory.
// Hilt automatically provides the implementation for @AssistedFactory interfaces.
// However, if P2pCommunicationManager itself had non-assisted dependencies that needed providing here,
// this module would be the place.

// For now, let's ensure Hilt knows about the factory. Usually, this is automatic.
// If P2pCommunicationManager is in a different module, you might need to ensure visibility.

// No explicit @Module or @Provides needed for the factory itself if using @AssistedFactory.
// NavigationService can just @Inject p2pCommunicationManagerFactory: P2pCommunicationManager.Factory
